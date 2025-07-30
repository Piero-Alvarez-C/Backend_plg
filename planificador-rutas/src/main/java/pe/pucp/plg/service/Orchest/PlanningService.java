package pe.pucp.plg.service.Orchest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
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

            // 1) Asigna urgentes (≤ 2h) vía método
            asignarPedidosUrgentes(candidatos, contexto, tiempoActual);

            // 2) Elimina los urgentes de candidatos
            candidatos.removeIf(p -> p.isProgramado());
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

                    /// A) Si está AVAILABLE → entrega directa
                    // A) Si está AVAILABLE → replan completo con todos sus pedidosCargados
                    if (mejor.getStatus() == CamionEstado.TruckStatus.AVAILABLE) {
                        // 1) Incluir el nuevo pedido en la carga del camión
                        mejor.getPedidosCargados().add(p);
                        p.setProgramado(true);

                        // 2) Construir la ruta completa y calcular el tiempo de entrega acumulado
                        List<Point> rutaCompleta = new ArrayList<>();
                        LocalDateTime scheduleTime = tiempoActual;
                        int cx = mejor.getX(), cy = mejor.getY();
                        for (Pedido q : mejor.getPedidosCargados()) {
                            // calcular segmento (respetando caso misma posición)
                            if (cx != q.getX() || cy != q.getY()) {
                                List<Point> seg = pathfindingService.buildManhattanPath(
                                        cx, cy, q.getX(), q.getY(),
                                        scheduleTime, contexto
                                );
                                // si seg es null, podrías saltar este pedido
                                if (seg != null) {
                                    rutaCompleta.addAll(seg);
                                    scheduleTime = scheduleTime.plusMinutes(seg.size());
                                }
                            }
                            // sumar tiempo de servicio
                            scheduleTime = scheduleTime.plusMinutes(TIEMPO_SERVICIO);
                            // avanzar posición
                            cx = q.getX();
                            cy = q.getY();
                        }

                        // 3) Configurar el camión con la ruta multi‐parada
                        mejor.setRuta(rutaCompleta);
                        mejor.setPasoActual(0);
                        mejor.setStatus(CamionEstado.TruckStatus.DELIVERING);
                        mejor.setTiempoLibre(scheduleTime);

                        // 4) Limpiar eventos previos sin capturar 'mejor' en el lambda
                        final String camionId = mejor.getPlantilla().getId();
                        contexto.getEventosEntrega().removeIf(ev ->
                                ev.getCamionId().equals(camionId)
                        );

                        // 5) Programar un EntregaEvent para cada parada, en orden
                        scheduleTime = tiempoActual;
                        cx = mejor.getX();
                        cy = mejor.getY();
                        for (Pedido q : mejor.getPedidosCargados()) {
                            // calcular viaje
                            if (cx != q.getX() || cy != q.getY()) {
                                List<Point> seg = pathfindingService.buildManhattanPath(
                                        cx, cy, q.getX(), q.getY(),
                                        scheduleTime, contexto
                                );
                                if (seg != null) {
                                    scheduleTime = scheduleTime.plusMinutes(seg.size());
                                }
                            }
                            // evento de llegada
                            contexto.getEventosEntrega().add(
                                    new EntregaEvent(scheduleTime, camionId, q)
                            );
                            // tiempo de servicio
                            scheduleTime = scheduleTime.plusMinutes(TIEMPO_SERVICIO);
                            cx = q.getX();
                            cy = q.getY();
                        }
                    }

                    // B) Si ya está DELIVERING → replan parcial
                    else if (mejor.getStatus() != CamionEstado.TruckStatus.BREAKDOWN && mejor.getStatus() != CamionEstado.TruckStatus.MAINTENANCE) {

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
                // 📣 Logging ACO
                System.out.println("⚙️ [ACO] Rutas generadas:");
                for (Ruta r : rutas) {
                    System.out.printf("   • Camión %s → pedidos: %s (total %d)%n",
                            r.getCamionId(),
                            r.getPedidoIds(),
                            r.getPedidoIds().size());
                }
                aplicarRutas(tiempoActual, rutas, sinAsignar, contexto);
                contexto.setRutas(rutas);
            }
        }   
        return tiempoActual;
    }
    private void asignarPedidosUrgentes(List<Pedido> candidatos,
                                        ExecutionContext contexto,
                                        LocalDateTime tiempoActual) {
        // 1) Filtrar pedidos urgentes (≤ 2 h restantes)
        List<Pedido> urgentes = candidatos.stream()
                .filter(p -> Duration.between(tiempoActual, p.getTiempoLimite()).toHours() <= 2)
                .collect(Collectors.toList());

        // 2) Para cada urgente, encontrar el camión con menor tiempo de viaje real
        for (Pedido p : urgentes) {
            CamionEstado mejorCamion = null;
            List<Point> mejorRuta = null;
            int mejorTiempo = Integer.MAX_VALUE;

            for (CamionEstado c : contexto.getCamiones()) {
                // Sólo camiones libres y con suficiente capacidad
                if (c.getStatus() != CamionEstado.TruckStatus.AVAILABLE) continue;
                if (c.getCapacidadDisponible() < p.getVolumen()) continue;
                if (!esDesvioValido(c, p, tiempoActual, contexto)) continue;

                // -- CASO ESPECIAL: mismo punto --
                Point origen  = new Point(c.getX(), c.getY());
                Point destino = new Point(p.getX(), p.getY());
                List<Point> ruta;
                int travelTime;
                if (origen.equals(destino)) {
                    // Pedido en el almacén, no hay desplazamiento
                    ruta       = Collections.emptyList();
                    travelTime = 0;
                } else {
                    // Ruta normal con A* (considera bloqueos)
                    ruta = pathfindingService.findPathAStar(
                            origen, destino, tiempoActual, contexto
                    );
                    if (ruta == null) {
                        // no hay ruta válida; saltamos este camión
                        continue;
                    }
                    travelTime = ruta.size();
                }

                // Comparar y quedarnos con el camión más rápido
                if (travelTime < mejorTiempo) {
                    mejorTiempo = travelTime;
                    mejorCamion = c;
                    mejorRuta   = ruta;
                }
            }

            // 3) Si encontramos un camión, programar la entrega urgente
            if (mejorCamion != null && mejorRuta != null) {
                LocalDateTime llegada     = tiempoActual.plusMinutes(mejorTiempo);
                LocalDateTime finServicio = llegada.plusMinutes(TIEMPO_SERVICIO);

                mejorCamion.setRuta(mejorRuta);
                mejorCamion.setPasoActual(0);
                mejorCamion.setStatus(CamionEstado.TruckStatus.DELIVERING);
                // Descontar volumen
                mejorCamion.setCapacidadDisponible(
                        mejorCamion.getCapacidadDisponible() - p.getVolumen()
                );
                mejorCamion.setTiempoLibre(finServicio);
                mejorCamion.getPedidosCargados().add(p);

                p.setProgramado(true);
                p.setHoraEntregaProgramada(finServicio);

                contexto.getEventosEntrega().add(
                        new EntregaEvent(finServicio, mejorCamion.getPlantilla().getId(), p)
                );
            }
        }
    }
    public void aplicarRutas(LocalDateTime tiempoActual, List<Ruta> rutas, List<Pedido> activos, ExecutionContext contexto) {
        // 📣 Logging pre-aplicación
        System.out.printf("🔧 Aplicando %d rutas ACO al estado real (antes de fallback)%n", rutas.size());
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
                    Point origen  = new Point(mejor.getX(), mejor.getY());
                    Point destino = new Point(p.getX(), p.getY());
                    List<Point> path;
                    if (origen.equals(destino)) {
                        path = Collections.emptyList();
                    } else {
                        path = pathfindingService.buildManhattanPath(mejor.getX(), mejor.getY(), p.getX(), p.getY(), tiempoActual, contexto);
                    }

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

                    // — construyo sólo el tramo de desvío con chequeo “misma posición” —
                    Point origen  = new Point(camion.getX(), camion.getY());
                    Point destino = new Point(p.getX(),      p.getY());
                    List<Point> caminoDesvio;
                    if (origen.equals(destino)) {
                        // Pedido en el mismo punto: ruta vacía
                        caminoDesvio = Collections.emptyList();
                    } else {
                        // Llamada normal al pathfinder
                        caminoDesvio = pathfindingService.buildManhattanPath(
                                camion.getX(), camion.getY(),
                                p.getX(), p.getY(),
                                tiempoActual,
                                contexto
                        );
                    }

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
                    //break;
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

                    incidentService.calcularPuntosAveria(camion, contexto);

                    // 3) Programar un EntregaEvent secuencial para cada pedido
                    LocalDateTime horaProximaAccion = tiempoActual;
                    cx = camion.getX();
                    cy = camion.getY();
                    for (Pedido p : camion.getPedidosCargados()) {
                        // — PATCH: chequeo “misma posición” antes de llamar al pathfinder —
                        Point origen  = new Point(cx, cy);
                        Point destino = new Point(p.getX(), p.getY());
                        List<Point> seg;
                        if (origen.equals(destino)) {
                            seg = Collections.emptyList();
                        } else {
                            seg = pathfindingService.buildManhattanPath(
                                    cx, cy,
                                    p.getX(), p.getY(),
                                    tiempoActual, contexto
                            );
                        }

                        // si seg == null, podrías saltarte o tratarlo, pero con cero-distancia no es null
                        if (seg != null) {
                            rutaCompleta.addAll(seg);
                        }

                        cx = p.getX();
                        cy = p.getY();
                    }

                    camion.setRuta(rutaCompleta);
                    camion.setPasoActual(0);
                    camion.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    incidentService.calcularPuntosAveria(camion, contexto);

                    // 3) Programar un EntregaEvent secuencial para cada pedido
                    horaProximaAccion = tiempoActual;
                    cx = camion.getX();
                    cy = camion.getY();
                    for (Pedido p : camion.getPedidosCargados()) {
                        // — PATCH: mismo chequeo para calcular pasos —
                        Point origen2  = new Point(cx,    cy);
                        Point destino2 = new Point(p.getX(), p.getY());
                        int pasos;
                        if (origen2.equals(destino2)) {
                            pasos = 0;
                        } else {
                            pasos = pathfindingService.buildManhattanPath(
                                    cx, cy,
                                    p.getX(), p.getY(),
                                    horaProximaAccion, contexto
                            ).size();
                        }

                        LocalDateTime horaLlegada = horaProximaAccion.plusMinutes(pasos);
                        contexto.getEventosEntrega().add(
                                new EntregaEvent(horaLlegada, camion.getPlantilla().getId(), p)
                        );
                        p.setHoraEntregaProgramada(horaLlegada);

                        horaProximaAccion = horaLlegada.plusMinutes(TIEMPO_SERVICIO);
                        cx = p.getX();
                        cy = p.getY();
                    }
                }
            }
            System.out.printf("   ✅ Camión %s: ahora en ruta (%d paradas)%n",
                    camion.getPlantilla().getId(),
                    camion.getPedidosCargados().size());
        }
        // C) Fallback: asignar los pedidos que ACO no programó
        Set<Integer> pedidosAsignados = rutas.stream()
                .flatMap(r -> r.getPedidoIds().stream())
                .collect(Collectors.toSet());

        for (Pedido p : activos) {
            if (p.isProgramado() || pedidosAsignados.contains(p.getId())) continue;

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
                // — PATCH: chequeo “misma posición” antes del build —
                Point origen3  = new Point(mejor.getX(), mejor.getY());
                Point destino3 = new Point(p.getX(),      p.getY());
                List<Point> path;
                if (origen3.equals(destino3)) {
                    path = Collections.emptyList();
                } else {
                    path = pathfindingService.buildManhattanPath(
                            mejor.getX(), mejor.getY(),
                            p.getX(), p.getY(),
                            tiempoActual, contexto
                    );
                }

                mejor.setRuta(path);
                mejor.setPasoActual(0);
                mejor.setStatus(CamionEstado.TruckStatus.DELIVERING);
                mejor.getPedidosCargados().add(p);
                p.setProgramado(true);

                int viaje = path.size();
                LocalDateTime fin = tiempoActual.plusMinutes(viaje + TIEMPO_SERVICIO);
                mejor.setTiempoLibre(fin);
                contexto.getEventosEntrega().add(
                        new EntregaEvent(fin, mejor.getPlantilla().getId(), p)
                );
                p.setHoraEntregaProgramada(fin);
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
