package pe.pucp.plg.service.Orchest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.awt.Point;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import pe.pucp.plg.model.common.EntregaEvent;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;
import pe.pucp.plg.service.algorithm.ACOPlanner;

@Service
public class PlanningService {

    private final PathfindingService pathfindingService;
    private final FleetService fleetService;
    private final IncidentService incidentService;
    private final ACOPlanner acoPlanner;

    private final int TIEMPO_SERVICIO = 15;

    @Autowired
    public PlanningService(PathfindingService pathfindingService, FleetService fleetService, IncidentService incidentService, ACOPlanner acoPlanner) {
        this.pathfindingService = pathfindingService;
        this.fleetService = fleetService;
        this.incidentService = incidentService;
        this.acoPlanner = acoPlanner;
    }

    public LocalDateTime replanificar(ExecutionContext contexto, List<CamionEstado> flotaEstado, List<Pedido> candidatos, LocalDateTime tiempoActual, boolean replanificar) {
        if (replanificar && !candidatos.isEmpty()) {
            /*System.out.printf("⏲️ t+%d: Replanificando, candidatos=%s%n",
                    tiempoActual, candidatos.stream()
                            .map(Pedido::getId).collect(Collectors.toList()));*/
            // Si flotaEstado está vacío, salimos sin tocar nada
            if (flotaEstado.isEmpty()) {
                return tiempoActual;
            }
            System.out.println("Se está replanificando...");

            // A) cancelar y desprogramar — sólo si hay camiones
            Set<Integer> ids = candidatos.stream().map(Pedido::getId).collect(Collectors.toSet());
            contexto.getEventosEntrega().removeIf(ev -> ev.getPedido()!=null && ids.contains(ev.getPedido().getId()));
            candidatos.forEach(p -> {
                p.setProgramado(false);
                p.setHoraEntregaProgramada(null);
                });

            //candidatos.sort(Comparator.comparing(Pedido::getTiempoLimite));
            // B) Desvío local con búsqueda del mejor camión
            List<Pedido> sinAsignar = new ArrayList<>();
            for (Pedido p : candidatos) {
                CamionEstado mejor = null;
                int mejorDist = Integer.MAX_VALUE;
                // Encuentra el mejor camión para desvío
                for (CamionEstado c : contexto.getCamiones()) {
                    if (c.getStatus() == CamionEstado.TruckStatus.UNAVAILABLE || c.getStatus() == CamionEstado.TruckStatus.BREAKDOWN || c.getStatus() == CamionEstado.TruckStatus.MAINTENANCE) continue;
                    if (c.getCapacidadDisponible() < p.getVolumen()) continue;
                    int dist = Math.abs(c.getX() - p.getX()) + Math.abs(c.getY() - p.getY());
                    if (esDesvioValido(c, p, tiempoActual, contexto) && dist < mejorDist) {
                        mejor = c;
                        mejorDist = dist;
                    }
                }
                if (mejor != null) {
                    // 1) Backup de ruta original
                    mejor.setRutaBackup(new ArrayList<>(mejor.getRutaActual()));
                    mejor.setPedidosBackup(new ArrayList<>(mejor.getPedidosCargados()));
                    mejor.setPedidoDesvio(p);

                    // 2) Insertar en pendientes
                    int idx = posicionOptimaDeInsercion(mejor, p, tiempoActual, contexto);
                    mejor.getPedidosCargados().add(idx, p);
                    p.setProgramado(true);

                    // A) Si está AVAILABLE → entrega directa
                    if (mejor.getStatus() == CamionEstado.TruckStatus.AVAILABLE) {
                        List<Point> ruta = pathfindingService.buildManhattanPath(
                                mejor.getX(), mejor.getY(),
                                p.getX(), p.getY(),
                                tiempoActual,
                                contexto
                        );
                        int tt       = ruta.size();
                        LocalDateTime tLlegada = tiempoActual.plusMinutes(tt);
                        mejor.setStatus(CamionEstado.TruckStatus.DELIVERING);
                        mejor.setTiempoLibre(tLlegada.plusMinutes(TIEMPO_SERVICIO));
                        mejor.setRuta(ruta);
                        mejor.setPasoActual(0);

                        // limpiar TODOS los eventos pendientes de este camión
                        CamionEstado cam = mejor;
                        if (cam.getStatus() != CamionEstado.TruckStatus.UNAVAILABLE) {
                            contexto.getEventosEntrega()
                                    .removeIf(ev -> ev.getCamionId().equals(cam.getPlantilla().getId()));
                        }

                        // Se crea el nuevo evento para el desvío
                        contexto.getEventosEntrega()
                                .add(new EntregaEvent(tLlegada, cam.getPlantilla().getId(), p));

                    }
                    // B) Si ya está DELIVERING → replan parcial
                    else if (mejor.getStatus() != CamionEstado.TruckStatus.BREAKDOWN || mejor.getStatus() != CamionEstado.TruckStatus.MAINTENANCE) {

                        // SI ESTABA RETURNING
                        if(mejor.getStatus() == CamionEstado.TruckStatus.RETURNING && mejor.getTanqueDestinoRecarga() != null) {
                            for(TanqueDinamico t : contexto.getTanques()) {
                                if (t.getPosX() == mejor.getTanqueDestinoRecarga().getPosX() &&
                                    t.getPosY() == mejor.getTanqueDestinoRecarga().getPosY()) {
                                    t.setDisponible(t.getDisponible() + mejor.getPlantilla().getCapacidadCarga() - mejor.getCapacidadDisponible());
                                    break;
                                }
                            }
                            mejor.setEnRetorno(false);
                            mejor.setReabastecerEnTanque(null);
                            mejor.setTanqueOrigen(mejor.getTanqueOrigenBackup());
                        }
                        // calcular camino al desvío
                        List<Point> caminoDesvio = pathfindingService.buildManhattanPath(
                                mejor.getX(), mejor.getY(),
                                p.getX(), p.getY(),
                                tiempoActual,
                                contexto
                        );
                        if (caminoDesvio == null) {
                            sinAsignar.add(p);
                            continue;
                        }

                        // tiempo de llegada al desvío
                        int tt = caminoDesvio.size();
                        LocalDateTime tLlegada   = tiempoActual.plusMinutes(tt);
                        // mantengo camión en DELIVERING y bloqueado hasta fin de servicio
                        mejor.setStatus(CamionEstado.TruckStatus.DELIVERING);
                        mejor.setTiempoLibre(tLlegada.plusMinutes(TIEMPO_SERVICIO));


                        mejor.getRutaActual().clear();
                        mejor.setRuta(new ArrayList<>(caminoDesvio));
                        mejor.setPasoActual(0);
                        //mejor.getHistory().addAll(caminoDesvio);

                        // limpiar TODOS los eventos pendientes de este camión
                        CamionEstado cam = mejor;
                        contexto.getEventosEntrega()
                                .removeIf(ev -> ev.getCamionId().equals(cam.getPlantilla().getId()));

                        // programar SOLO el evento de llegada al pedido desviado
                        contexto.getEventosEntrega()
                                .add(new EntregaEvent(tLlegada, cam.getPlantilla().getId(), p));

                    }

                } else {
                    sinAsignar.add(p);
                }
            }


            // C) El resto va al ACO habitual
            if (!sinAsignar.isEmpty()) {
                /*System.out.printf("📦 ACO recibe pedidos sin asignar: %s%n",
                        sinAsignar.stream().map(Pedido::getId).collect(Collectors.toList()));*/
                sinAsignar.removeIf(p -> p.isProgramado() || p.isAtendido());
                List<Ruta> rutas = acoPlanner.planificarRutas(sinAsignar, flotaEstado, tiempoActual, contexto);

                aplicarRutas(tiempoActual, rutas, sinAsignar, contexto);
                contexto.setRutas(rutas);
            }
        }   
        return tiempoActual;
    }
    
    public void aplicarRutas(LocalDateTime tiempoActual, List<Ruta> rutas, List<Pedido> activos, ExecutionContext contexto) {
        rutas.removeIf(r -> r.getPedidoIds() == null || r.getPedidoIds().isEmpty());
        if (rutas.isEmpty()) {
            // Fallback: para cada pedido pendiente, busca el camión disponible más cercano
            for (Pedido p : activos) {
                CamionEstado mejor = null;
                int distMin = Integer.MAX_VALUE;
                for (CamionEstado c : contexto.getCamiones()) {
                    if (c.getStatus() != CamionEstado.TruckStatus.AVAILABLE) continue;
                    if (c.getCapacidadDisponible() < p.getVolumen()) continue;
                    int d = Math.abs(c.getX() - p.getX()) + Math.abs(c.getY() - p.getY());
                    if (d < distMin) {
                        distMin = d;
                        mejor = c;
                    }
                }
                if (mejor != null) {
                    // Asignación simple: construye ruta directa y programa entrega
                    List<Point> path = pathfindingService.buildManhattanPath(mejor.getX(), mejor.getY(), p.getX(), p.getY(), tiempoActual, contexto);
                    mejor.setRuta(path);    
                    mejor.setPasoActual(0);
                    mejor.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    mejor.getPedidosCargados().add(p);
                    p.setProgramado(true);
                    int viaje = path.size();

                    contexto.getEventosEntrega().add(new EntregaEvent(tiempoActual.plusMinutes(viaje + TIEMPO_SERVICIO), mejor.getPlantilla().getId(), p));
                    p.setHoraEntregaProgramada(tiempoActual.plusMinutes(viaje + TIEMPO_SERVICIO));
                }
            }
            return;
        }

        // A) Filtrar rutas que no caben en la flota real
        for (Iterator<Ruta> itR = rutas.iterator(); itR.hasNext(); ) {
            Ruta r = itR.next();
            CamionEstado real = fleetService.findCamion(r.getCamionId(), contexto);
            double disponible = real.getCapacidadDisponible();
            boolean allFit = true;
            for (int idx : r.getPedidoIds()) {
                if (disponible < activos.get(idx).getVolumen()) {
                    allFit = false;
                    break;
                }
                disponible -= activos.get(idx).getVolumen();
            }
            if (!allFit) {
                itR.remove();
            }
        }
        // B) Aplicar cada ruta al estado real
        for (Ruta ruta : rutas) {
            CamionEstado camion = fleetService.findCamion(ruta.getCamionId(), contexto);

            boolean deliveringOrReturning =
                    (!camion.getRutaActual().isEmpty() && camion.getStatus() == CamionEstado.TruckStatus.DELIVERING)
                            || camion.getStatus() == CamionEstado.TruckStatus.RETURNING;

            if (deliveringOrReturning) {
                // 1) Si venía retornando, cancela el evento de retorno y limpia estado
                if (camion.getStatus() == CamionEstado.TruckStatus.RETURNING) {
                    for(TanqueDinamico t : contexto.getTanques()) {
                        if (t.getPosX() == camion.getTanqueDestinoRecarga().getPosX() &&
                            t.getPosY() == camion.getTanqueDestinoRecarga().getPosY()) {
                            t.setDisponible(t.getDisponible() + camion.getPlantilla().getCapacidadCarga() - camion.getCapacidadDisponible());
                            break;
                        }
                    }
                    contexto.getEventosEntrega()
                            .removeIf(ev -> ev.getCamionId().equals(camion.getPlantilla().getId()) && ev.getPedido() == null);
                    camion.setEnRetorno(false);
                    camion.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                    camion.getRutaActual().clear();
                    camion.setPasoActual(0);
                    camion.getPedidosCargados().clear();
                    
                    camion.setReabastecerEnTanque(null);
                    camion.setTanqueOrigen(camion.getTanqueOrigenBackup());
                }

                // 2) Encolar y programar un solo desvío
                for (int idx : ruta.getPedidoIds()) {
                    Pedido p = activos.get(idx);
                    if (p.isProgramado() || camion.getPedidosCargados().contains(p)) continue;
                    // — nuevo check de capacidad —
                    if (p.getVolumen() > camion.getCapacidadDisponible()) {
                        continue;
                    }
                    if (!esDesvioValido(camion, p, tiempoActual, contexto)) continue;

                    // — reservo espacio —
                    camion.setCapacidadDisponible(camion.getCapacidadDisponible() - p.getVolumen());

                    // — construyo sólo el tramo de desvío y encolo en rutaPendiente —
                    List<Point> caminoDesvio = pathfindingService.buildManhattanPath(
                            camion.getX(), camion.getY(),
                            p.getX(), p.getY(),
                            tiempoActual,
                            contexto
                    );
                    if (caminoDesvio != null && camion.getStatus() != CamionEstado.TruckStatus.BREAKDOWN && camion.getStatus() != CamionEstado.TruckStatus.MAINTENANCE) {
                        // 1) Reemplaza la ruta actual por el tramo de desvío
                        camion.getRutaActual().clear();
                        camion.getRutaActual().addAll(caminoDesvio);
                        camion.setPasoActual(0);

                        // 2) Programa el evento de entrega
                        // ── PARCHE: programar fin de servicio del pedido desviado ──
                        int ttDesvio = caminoDesvio.size();
                        LocalDateTime finServicio = tiempoActual.plusMinutes(ttDesvio + TIEMPO_SERVICIO);
                        // No cambiar el estado si el camión está averiado
                            camion.setStatus(CamionEstado.TruckStatus.DELIVERING);

                        camion.setTiempoLibre(finServicio);
                        contexto.getEventosEntrega().add(
                            new EntregaEvent(finServicio, camion.getPlantilla().getId(), p)  // p = pedidoDesvio
                            );
                        p.setHoraEntregaProgramada(finServicio);
                        // ────────────────────────────────────────────────────────────────────────────
                        p.setProgramado(true);
                        incidentService.calcularPuntosAveria(camion, contexto);
                    }
                    break;
                }

            } else {
                // 1) Limpio rutaPendiente y encolo TODOS los pedidos de la ruta
                camion.getPedidosCargados().clear();
                for (int idx : ruta.getPedidoIds()) {
                    Pedido p = activos.get(idx);
                    if (p.isProgramado() || p.isAtendido()) continue;
                    camion.getPedidosCargados().add(p);
                    p.setProgramado(true);
                }

                // 2) Si encolé algo, construyo la nueva ruta completa
                if (!camion.getPedidosCargados().isEmpty() && (camion.getStatus() != CamionEstado.TruckStatus.BREAKDOWN && camion.getStatus() != CamionEstado.TruckStatus.MAINTENANCE)) {
                    List<Point> rutaCompleta = new ArrayList<>();
                    int cx = camion.getX(), cy = camion.getY();
                    for (Pedido p : camion.getPedidosCargados()) {
                        List<Point> seg = pathfindingService.buildManhattanPath(cx, cy, p.getX(), p.getY(), tiempoActual, contexto);
                        rutaCompleta.addAll(seg);
                        cx = p.getX();
                        cy = p.getY();
                    }
                    camion.setRuta(rutaCompleta);
                    camion.setPasoActual(0);
                    camion.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    //camion.getHistory().addAll(rutaCompleta);

                    TanqueDinamico tanqueDePartida = contexto.getTanques().stream()
                        .filter(t -> t.getPosX() == camion.getX() && t.getPosY() == camion.getY())
                        .findFirst()
                        .orElse(contexto.getTanques().get(0)); // Por defecto, la planta principal

                    // FIJAR el origen y el backup ANTES de que el camión se mueva
                    camion.setTanqueOrigen(tanqueDePartida);
                    camion.setTanqueOrigenBackup(tanqueDePartida); // El backup es igual al origen al inicio


                    incidentService.calcularPuntosAveria(camion, contexto);

                    // 3) Programar un EntregaEvent secuencial para cada pedido
                    LocalDateTime horaProximaAccion = tiempoActual;
                    cx = camion.getX();
                    cy = camion.getY();
                    for (Pedido p : camion.getPedidosCargados()) {
                        int pasos = pathfindingService.buildManhattanPath(cx, cy, p.getX(), p.getY(), horaProximaAccion, contexto).size();
                        
                        // Calculate the arrival time for this specific stop
                        LocalDateTime horaLlegada = horaProximaAccion.plusMinutes(pasos);
                        
                        // Schedule the ARRIVAL event
                        contexto.getEventosEntrega().add(new EntregaEvent(horaLlegada, camion.getPlantilla().getId(), p));
                        p.setHoraEntregaProgramada(horaLlegada);

                        // The next leg of the journey can only start after this service is complete
                        horaProximaAccion = horaLlegada.plusMinutes(TIEMPO_SERVICIO);
                        
                        // Update the starting point for the next iteration
                        cx = p.getX();
                        cy = p.getY();
                    }
                }
            }
        }

    }

    // ------------------------------------------------------------
    // Métodos privados auxiliares copiados de ACOPlanner original
    // ------------------------------------------------------------
    private boolean esDesvioValido(CamionEstado c, Pedido p, LocalDateTime tiempoActual, ExecutionContext contexto) {
        // 1) Capacidad real remanente = total – lo ya en rutaPendiente
        double capacidadTotal = c.getCapacidadDisponible();
        double volumenEnRuta = c.getPedidosCargados().stream()
                .mapToDouble(Pedido::getVolumen)
                .sum();
        double disponible = capacidadTotal - volumenEnRuta;

        // 2) Simular tiempos
        LocalDateTime hora = tiempoActual;
        int prevX = c.getX(), prevY = c.getY();

        // — Primer tramo: al nuevo pedido —
        List<Point> pathToNew = pathfindingService.buildManhattanPath(prevX, prevY, p.getX(), p.getY(), hora, contexto);
        if (pathToNew == null) return false;
        hora = hora.plusMinutes(pathToNew.size());
        hora = hora.plusMinutes(TIEMPO_SERVICIO);
        if (hora.isAfter(p.getTiempoLimite())) return false;

        // 3) Chequeo de capacidad para el nuevo pedido
        if (disponible < p.getVolumen()) return false;
        disponible -= p.getVolumen();
        prevX = p.getX();
        prevY = p.getY();

        // — Ahora los pedidos que ya estaba llevando —
        for (Pedido orig : c.getPedidosCargados()) {
            List<Point> pathSeg = pathfindingService.buildManhattanPath(prevX, prevY, orig.getX(), orig.getY(), hora, contexto);
            if (pathSeg == null) return false;
            hora = hora.plusMinutes(pathSeg.size());
            hora = hora.plusMinutes(TIEMPO_SERVICIO);
            if (hora.isAfter(orig.getTiempoLimite())) return false;

            disponible -= orig.getVolumen();
            if (disponible < 0) return false;

            prevX = orig.getX();
            prevY = orig.getY();
        }
        return true;
    }

    private int posicionOptimaDeInsercion(CamionEstado c, Pedido pNuevo, LocalDateTime tiempoActual, ExecutionContext contexto) {
        List<Pedido> originales = c.getPedidosCargados();
        int mejorIdx = originales.size();
        LocalDateTime mejorHoraEntrega = LocalDateTime.MAX;

        // Capacidad y posición de arranque reales del camión
        double capacidadOriginal = c.getCapacidadDisponible();
        int x0 = c.getX(), y0 = c.getY();

        // Probar cada posible posición de inserción
        for (int idx = 0; idx <= originales.size(); idx++) {
            double disponible = capacidadOriginal;
            LocalDateTime hora = tiempoActual;
            int simX = x0, simY = y0;

            // Montamos la lista de pedidos en el orden de prueba
            List<Pedido> prueba = new ArrayList<>(originales);
            prueba.add(idx, pNuevo);

            boolean valido = true;
            // Recorremos cada segmento (pedido) con ruta real
            for (Pedido q : prueba) {
                // 1) Construir la ruta real (bloqueos-aware) desde (simX,simY) hasta q
                List<Point> path = pathfindingService.buildManhattanPath(simX, simY, q.getX(), q.getY(), hora, contexto);
                if (path == null) {
                    valido = false;
                    break;
                }
                // 2) Tiempo de viaje = número de pasos
                hora = hora.plusMinutes(path.size());
                // 3) Tiempo de servicio (descarga)
                hora = hora.plusMinutes(TIEMPO_SERVICIO);
                // 4) Comprobar deadline
                if (hora.isAfter(q.getTiempoLimite())) {
                    valido = false;
                    break;
                }
                // 5) Restar volumen al disponible
                disponible -= q.getVolumen();
                if (disponible < 0) {
                    valido = false;
                    break;
                }
                // 6) Avanzar “virtual” a la posición del pedido
                simX = q.getX();
                simY = q.getY();
            }

            // Si democrático y acaba antes (mejor horaEntrega), guardamos índice
            if (valido && hora.isBefore(mejorHoraEntrega)) {
                mejorHoraEntrega = hora;
                mejorIdx = idx;
            }
        }

        return mejorIdx;
    }


}
