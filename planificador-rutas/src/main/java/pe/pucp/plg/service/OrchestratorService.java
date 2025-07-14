package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.dto.*;
import pe.pucp.plg.dto.enums.EventType;
import pe.pucp.plg.model.common.Averia;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.EntregaEvent;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;
import pe.pucp.plg.service.algorithm.ACOPlanner;
import pe.pucp.plg.util.ResourceLoader;
import pe.pucp.plg.util.MapperUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.awt.Point;

@Service
public class OrchestratorService {

    private final ACOPlanner acoPlanner;
    private final EventPublisherService eventPublisher;

    private static final int TIEMPO_SERVICIO = 15; 
    private static final int INTERVALO_REPLAN = 40;
    private static final int UMBRAL_VENCIMIENTO = 60;

    private int countReplan;

    private static class Node {
        Point position;
        Node parent;
        int gCost; // Cost from start to current node
        int hCost; // Heuristic cost from current node to end
        int fCost; // Total cost (gCost + hCost)

        Node(Point position) {
            this.position = position;
            this.gCost = Integer.MAX_VALUE;
        }

        Node(Point position, Node parent, int gCost, int hCost) {
            this.position = position;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
            this.fCost = gCost + hCost;
        }
    }

    @Autowired
    public OrchestratorService(EventPublisherService eventPublisher) {
        this.acoPlanner = new ACOPlanner();
        this.eventPublisher = eventPublisher;
        this.countReplan = 0;
    }

    /**
     * Advances the simulation identified by simulationId by one minute.
     * @param simulationId The ID of the simulation to step forward.
     * @return The new current time of the simulation.
     */
    public LocalDateTime stepOneMinute(ExecutionContext contexto, String simulationId) {
        // corregido
        LocalDateTime tiempoActual = contexto.getCurrentTime() != null ?
                contexto.getCurrentTime().plusMinutes(1).withSecond(0).withNano(0) : LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        contexto.setCurrentTime(tiempoActual);
        // Para verificaciones que necesitan un día nuevo
        boolean esMediaNoche = tiempoActual.getHour() == 0 && tiempoActual.getMinute() == 0 && !tiempoActual.equals(contexto.getFechaInicio().atStartOfDay());
        boolean replanificar = tiempoActual.equals(contexto.getFechaInicio().atStartOfDay()); // Replanificar al inicio siempre

        // 1) Recarga de tanques intermedios cada vez que currentTime % 1440 == 0 (inicio de día)
        if (esMediaNoche) {
            //System.out.println("⛽ Recarga diaria de tanques en " + tiempoActual);
            for (TanqueDinamico tq : contexto.getTanques()) {
                tq.setDisponible(tq.getCapacidadTotal());
            }

            // Reiniciar el estado de las averías de archivo para que puedan repetirse diariamente
            contexto.getAveriasAplicadas().clear();
            contexto.getPuntosAveria().clear();

            contexto.getBloqueosPorDia().removeIf(b -> b.getEndTime().isBefore(tiempoActual));
            
            // Determinar qué día estamos y cargar datos para ese día
            LocalDate fechaActual = tiempoActual.toLocalDate();
            long diaActual = fechaActual.toEpochDay() - contexto.getFechaInicio().toEpochDay() + 1;
            //System.out.println("📅 Día " + diaActual + " de la simulación, cargando nuevos datos...");
            
            // Solo cargamos datos nuevos si estamos dentro del período de simulación
            if (diaActual <= contexto.getDuracionDias()) {
                //System.out.println("📅 Cargando datos para el día: " + fechaActual);
                
                // Cargar pedidos y bloqueos para este día
                List<Pedido> nuevoPedidos = ResourceLoader.cargarPedidosParaFecha(fechaActual);
                List<Bloqueo> nuevoBloqueos = ResourceLoader.cargarBloqueosParaFecha(fechaActual);

                /*System.out.printf("🔄 Día %d: Hay %d nuevos pedidos y %d bloqueos%n", 
                        diaActual, nuevoPedidos.size(), nuevoBloqueos.size());*/

                // Añadir los nuevos pedidos al mapa de pedidos por tiempo
                for (Pedido p : nuevoPedidos) {
                    contexto.getPedidosPorTiempo().computeIfAbsent(p.getTiempoCreacion(), k -> new ArrayList<>()).add(p);
                }
                
                // Añadir los nuevos bloqueos
                for (Bloqueo b : nuevoBloqueos) {
                    contexto.getBloqueosPorTiempo().computeIfAbsent(b.getStartTime(), k -> new ArrayList<>()).add(b);
                    contexto.getBloqueosPorDia().add(b);
                }
                
                /*System.out.printf("🔄 Día %d: Cargados %d nuevos pedidos y %d bloqueos%n", 
                        diaActual, contexto.getPedidos().size(), contexto.getBloqueos().size());*/
                
                // Si hay nuevos datos, replanificar
                if (!nuevoPedidos.isEmpty() || !nuevoBloqueos.isEmpty()) {
                    replanificar = true;
                }
            }
        }

        actualizarBloqueosActivos(contexto, tiempoActual);

        // ——— 2) DISPARAR SERVICIO SI LLEGÓ POR “PASOS”, por si falla el evento ———
        //for (CamionEstado c : contexto.getCamiones()) {
        //    // Si estaba en ruta y ya acabó todos los pasos, arranca el servicio
        //    if (c.getStatus() == CamionEstado.TruckStatus.DELIVERING  && !c.tienePasosPendientes()) {
        //        c.setStatus(CamionEstado.TruckStatus.UNAVAILABLE);
        //        int finServicio = tiempoActual + TIEMPO_SERVICIO;
        //        c.setLibreEn(finServicio);
        //        System.out.printf("⏲️ t+%d: Camión %s inicia servicio de entrega (backup), libre en t+%d%n", tiempoActual, c.getId(), finServicio);
        //        // no hacemos continue aquí, porque luego triggerScheduledDeliveries lo completará
        //    }
        //}

        

        // 3) Avanzar o procesar retorno y entregas por separado
        for (CamionEstado c : contexto.getCamiones()) {
            // 0) Está averiado => no procesar en absoluto
            if (c.getStatus() == CamionEstado.TruckStatus.BREAKDOWN) {
                // Camión averiado, no se procesa en ningún caso
                continue;
            }
            
            // 1) Está descargando/recargando => no avanza
            if (c.getStatus() == CamionEstado.TruckStatus.UNAVAILABLE){
                /*System.out.printf("⏱️ t+%d: Camión %s en servicio, libre en t+%d%n",
                        tiempoActual, c.getPlantilla().getId(), c.getTiempoLibre());*/
                continue;
            }

            // 2) Mover camiones que están en ruta (entregando o retornando)
            if (c.getStatus() == CamionEstado.TruckStatus.DELIVERING) {
                if (c.tienePasosPendientes()) {
                    c.avanzarUnPaso();
                    contexto.setTotalDistanciaRecorrida(contexto.getTotalDistanciaRecorrida() + 1);
                }
            }

            // Lógica de retorno (sin avance, que ya se hizo arriba)
            if (c.getStatus() == CamionEstado.TruckStatus.RETURNING) {
                if (c.tienePasosPendientes()) {
                    c.avanzarUnPaso();
                    contexto.setTotalDistanciaRecorrida(contexto.getTotalDistanciaRecorrida() + 1);
                    /*System.out.printf("t+%d: → Camión %s avanza (retorno) a (%d,%d)%n",tiempoActual,
                            c.getPlantilla().getId(), c.getX(), c.getY());*/
                } else {
                    // llegó al depósito: programa recarga 15'
                    c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                    c.setCapacidadDisponible(c.getPlantilla().getCapacidadCarga());
                    c.setCombustibleDisponible(c.getPlantilla().getCapacidadCombustible()); 
                    c.setTiempoLibre(tiempoActual.plusMinutes(TIEMPO_SERVICIO));
                    c.getPedidosCargados().clear();
                    /*System.out.printf("🔄 t+%d: Camión %s llegó a planta, recargando hasta t+%d%n",
                            tiempoActual, c.getPlantilla().getId(), c.getTiempoLibre());*/
                }
                continue;
            }

            // 3) Ruta de entrega/desvío
            if (c.getStatus() == CamionEstado.TruckStatus.DELIVERING
                    && c.tienePasosPendientes()) {
                //c.avanzarUnPaso();
                if (c.getPedidoDesvio() != null) {
                    /*System.out.printf("t+%d:→ Camión %s avanza (desvío) a (%d,%d)%n", tiempoActual,
                            c.getPlantilla().getId(), c.getX(), c.getY());*/
                } else {
                    /*System.out.printf("t+%d:→ Camión %s avanza (entrega) a (%d,%d)%n", tiempoActual,
                            c.getPlantilla().getId(), c.getX(), c.getY());*/
                }
                continue;
            }

            // 4) AVAILABLE con ruta vacía → simplemente espera asignación
        }
        // 2) Disparar eventos de entrega programados para este minuto
        triggerScheduledDeliveries(tiempoActual, contexto);


        // 5) Incorporar nuevos pedidos que llegan en este minuto
        List<Pedido> nuevos = contexto.getPedidosPorTiempo().remove(tiempoActual);
        if (nuevos == null) {
            nuevos = Collections.emptyList();
        }

        // 5.a) Calcular capacidad máxima de un camión (suponiendo que todos tienen la misma capacidad)
        double capacidadMaxCamion = contexto.getCamiones().stream()
                .mapToDouble(c -> c.getPlantilla().getCapacidadCarga())
                .max()
                .orElse(0);

        List<Pedido> pedidosAInyectar = new ArrayList<>();
        for (Pedido p : nuevos) {
            double volumenRestante = p.getVolumen();

            if (volumenRestante > capacidadMaxCamion) {
                // 🛠️ Dividir en sub-pedidos de ≤ capacidadMaxCamion
                while (volumenRestante > 0) {
                    double vol = Math.min(capacidadMaxCamion, volumenRestante);
                    int subId = contexto.generateUniquePedidoId();
                    Pedido sub = new Pedido(
                            subId,
                            tiempoActual,
                            p.getX(),
                            p.getY(),
                            vol,
                            p.getTiempoLimite()
                    );
                    pedidosAInyectar.add(sub);
                    volumenRestante -= vol;
                }
            } else {
                // cabe entero en un camión
                pedidosAInyectar.add(p);
            }
        }

        // 5.b) Añadir realmente los pedidos (reemplazo de los nuevos originales)
        contexto.getPedidos().addAll(pedidosAInyectar);

        if (!pedidosAInyectar.isEmpty()) replanificar = true;
        if (countReplan == INTERVALO_REPLAN) {
            System.out.println("Replanificando");   
            replanificar = true;
            countReplan = 0;
        }

        // (B) pedidos próximos a vencer: umbral en minutos
        boolean hayUrgentes = contexto.getPedidos().stream()
                .filter(p -> !p.isAtendido() && !p.isDescartado())
                .anyMatch(p -> p.getTiempoLimite().minusMinutes(UMBRAL_VENCIMIENTO).isBefore(tiempoActual));
        if (hayUrgentes) {
            replanificar = true;
        }
        // ----------------------------------------------
        // 6) Comprobar colapso: pedidos vencidos
        Iterator<Pedido> itP = contexto.getPedidos().iterator();
        boolean haColapsado = false;
        while (itP.hasNext()) {
            Pedido p = itP.next();
            if (!p.isAtendido() && !p.isDescartado() && !p.isEnEntrega() && (tiempoActual.isAfter(p.getTiempoLimite().plusMinutes(2)) || tiempoActual.isAfter(p.getTiempoLimite()))) {
                /*System.out.printf("💥 Colapso en t+%d, pedido %d incumplido%n",
                        tiempoActual, p.getId());*/
                // Marca y elimina para no repetir el colapso
                contexto.setPedidoColapso(p.getId() + " (" + p.getX() + "," + p.getY() + ")");
                p.setDescartado(true);
                itP.remove();
                haColapsado = true;
            }
        }
        // COLAPSO
        if(haColapsado && !contexto.isIgnorarColapso()) {
            // Si ha colapsado y no se ignora, destruir la simulacion
            System.out.printf("💥 Colapso detectado en %s, finalizando simulación%n", tiempoActual);
            eventPublisher.publicarEventoSimulacion(simulationId, EventDTO.of(EventType.SIMULATION_COLLAPSED, new ReporteDTO(contexto.getTotalPedidosEntregados(), contexto.getTotalDistanciaRecorrida(), contexto.getPedidoColapso())));
            return null;
        }

        // 7) Averías por turno (T1, T2, T3)
        //replanificar |= procesarAverias(contexto, tiempoActual);
        replanificar |= procesarAverias(contexto, tiempoActual);
        // 8) Construir estado “ligero” de la flota disponible para ACO
        List<CamionEstado> flotaEstado = contexto.getCamiones().stream()
                .filter(c -> c.getStatus() != CamionEstado.TruckStatus.UNAVAILABLE
                        && c.getStatus() != CamionEstado.TruckStatus.BREAKDOWN
                        && c.getPedidosCargados().isEmpty()            // no tiene entregas encoladas
                        && c.getPedidoDesvio() == null)               // no está en medio de un desvío
                .map(c -> {
                    CamionEstado est = new CamionEstado(c);
                    return est;
                })
                .collect(Collectors.toList());

        if (replanificar && flotaEstado.isEmpty()) {
            if(flotaEstado.isEmpty()) {
                System.out.printf("Ningún camión disponible (ni en ventana) → replanificación pospuesta%n",
                                    tiempoActual);
            }
            replanificar = false;
        }

        // 9) Determinar candidatos a replanificar
        List<Pedido> pendientes = contexto.getPedidos().stream()
                .filter(p -> !p.isAtendido() && !p.isDescartado() && !p.isProgramado() && p.getTiempoCreacion().isBefore(tiempoActual))
                .collect(Collectors.toList());

        List<Pedido> candidatos = pendientes;
        // 10) Replanificación ACO si procede
        if (replanificar && !candidatos.isEmpty()) {
            /*System.out.printf("⏲️ t+%d: Replanificando, candidatos=%s%n",
                    tiempoActual, candidatos.stream()
                            .map(Pedido::getId).collect(Collectors.toList()));*/
            // Si flotaEstado está vacío, salimos sin tocar nada
            if (flotaEstado.isEmpty()) {
                return tiempoActual;
            }

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
                    if (c.getStatus() == CamionEstado.TruckStatus.UNAVAILABLE || c.getStatus() == CamionEstado.TruckStatus.BREAKDOWN) continue;
                    if (c.getCapacidadDisponible() < p.getVolumen()) continue;
                    int dist = Math.abs(c.getX() - p.getX()) + Math.abs(c.getY() - p.getY());
                    if (esDesvioValido(c, p, tiempoActual, contexto) && dist < mejorDist) {
                        //if(p.getTiempoLimite() == null || c.getPedidosCargados().size() == 0) {
                            mejor = c;
                            mejorDist = dist;
                        //} //else if (c.getPedidosCargados().get(0).getTiempoLimite().isAfter(p.getTiempoLimite())) {
                            //mejor = c;
                            //mejorDist = dist;   
                        //}
                        
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
                        List<Point> ruta = buildManhattanPath(
                                mejor.getX(), mejor.getY(),
                                p.getX(), p.getY(),
                                tiempoActual,
                                contexto
                        );
                        int tt       = (int)Math.ceil(ruta.size() * (60.0 / 50.0));
                        LocalDateTime tLlegada = tiempoActual.plusMinutes(tt);
                        //System.out.printf(">>> DEBUG SCHEDULING-AVAIL: pedido #%d a t+%d%n",
                        //        p.getId(), tLlegada);
                        mejor.setStatus(CamionEstado.TruckStatus.DELIVERING);
                        mejor.setTiempoLibre(tLlegada.plusMinutes(TIEMPO_SERVICIO));
                        mejor.setRuta(ruta);
                        mejor.setPasoActual(0);
                        //mejor.getHistory().addAll(ruta);

                        // limpiar TODOS los eventos pendientes de este camión
                        CamionEstado cam = mejor;
                        contexto.getEventosEntrega()
                                .removeIf(ev -> ev.getCamionId().equals(cam.getPlantilla().getId()));

                        // programar SOLO el evento de llegada
                        contexto.getEventosEntrega()
                                .add(new EntregaEvent(tLlegada, cam.getPlantilla().getId(), p));
                    }
                    // B) Si ya está DELIVERING → replan parcial
                    else if (mejor.getStatus() != CamionEstado.TruckStatus.BREAKDOWN) {

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
                        List<Point> caminoDesvio = buildManhattanPath(
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
                        int tt = (int) Math.ceil(caminoDesvio.size() * (60.0 / 50.0));
                        LocalDateTime tLlegada   = tiempoActual.plusMinutes(tt);
                        // mantengo camión en DELIVERING y bloqueado hasta fin de servicio
                        mejor.setStatus(CamionEstado.TruckStatus.DELIVERING);
                        mejor.setTiempoLibre(tLlegada.plusMinutes(TIEMPO_SERVICIO));

                        //System.out.printf(">>> DEBUG SCHEDULING-PARCIAL: pedido #%d a t+%d%n",p.getId(), tLlegada);

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

                        /*System.out.printf(
                                "🔀 t+%d: Pedido #%d insertado en %s, recalculando ruta a desvío + resto%n",
                                tiempoActual, p.getId(), mejor.getPlantilla().getId()
                        );*/
                    }

                    /*System.out.printf(
                            "🔀 t+%d: Pedido #%d asignado a Camión %s (desvío)%n",
                            tiempoActual, p.getId(), mejor.getPlantilla().getId()
                    );*/
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
                /*System.out.printf("    → Rutas ACO para %s%n",
                        rutas.stream()
                                .flatMap(r -> r.getPedidoIds().stream())
                                .map(i -> sinAsignar.get(i).getId())
                                .collect(Collectors.toList()));*/

                aplicarRutas(tiempoActual, rutas, sinAsignar, contexto);
                contexto.setRutas(rutas);
            }
        }
        countReplan++;
        //generarPuntosAverias(contexto);
        //System.out.println("[DEBUG] Puntos de avería generados: " + contexto.getPuntosAveria());
        EventDTO estadoActual = EventDTO.of(EventType.SNAPSHOT, MapperUtil.toSnapshotDTO(contexto));
        eventPublisher.publicarEventoSimulacion(simulationId, estadoActual);
        return contexto.getCurrentTime();        
    }
    
    // 2) Disparar eventos de entrega programados para este minuto
    private void triggerScheduledDeliveries(LocalDateTime tiempoActual, ExecutionContext contexto) {
        List<EntregaEvent> nuevosEventos = new ArrayList<>();

    // Mientras haya eventos en la cola Y el siguiente evento sea para AHORA
        while (!contexto.getEventosEntrega().isEmpty() && 
            contexto.getEventosEntrega().peek().time.equals(tiempoActual)) {

            // Saca el evento de la cola
            EntregaEvent ev = contexto.getEventosEntrega().poll();
            
            // Compara el valor del tiempo, no la referencia del objeto
            if (!ev.time.equals(tiempoActual)) {
                continue;
            }

            CamionEstado camion = findCamion(ev.getCamionId(), contexto);
            Pedido pedido = ev.getPedido();
        
            // Si el camión está averiado, ignorar cualquier evento programado
            if (camion != null && camion.getStatus() == CamionEstado.TruckStatus.BREAKDOWN) {
                System.out.println("🔴 Evento ignorado: Camión " + camion.getPlantilla().getId() + 
                              " está averiado y no puede procesar eventos.");
                continue;
            }

            // CASO A: El evento es un retorno a la planta (no hay pedido)
            if (pedido == null) {
                startReturn(camion, tiempoActual, nuevosEventos, contexto);
                continue;
            }

            if(camion == null) {
                System.out.printf("❗ ERROR: Camión %s no encontrado para evento de entrega en %s%n",
                        ev.getCamionId(), tiempoActual);
                continue;
            }

            // CASO B: El evento es una LLEGADA al cliente
            if (camion.getStatus() == CamionEstado.TruckStatus.DELIVERING) {
                System.out.printf("➡️  LLEGADA: Camión %s llegó a pedido %d en %s. Inicia servicio.%n", camion.getPlantilla().getId(), pedido.getId(), tiempoActual);
                
                camion.setX(pedido.getX());
                camion.setY(pedido.getY());
                camion.setStatus(CamionEstado.TruckStatus.UNAVAILABLE); // Ocupado durante el servicio
                pedido.setEnEntrega(true);
                
                // Se agenda el evento de "Fin de Servicio" para más tarde
                LocalDateTime finServicio = tiempoActual.plusMinutes(TIEMPO_SERVICIO);
                camion.setTiempoLibre(finServicio);
                nuevosEventos.add(new EntregaEvent(finServicio, camion.getPlantilla().getId(), pedido));
            } 
            // CASO C: El evento es un FIN DE SERVICIO
            else if (camion.getStatus() == CamionEstado.TruckStatus.UNAVAILABLE) {
                System.out.printf("✅ FIN SERVICIO: Camión %s completó pedido %d en %s.%n", camion.getPlantilla().getId(), pedido.getId(), tiempoActual);
                
                double antes = camion.getCapacidadDisponible();
                camion.setCapacidadDisponible(antes - pedido.getVolumen());
                pedido.setAtendido(true); 
                contexto.setTotalPedidosEntregados(contexto.getTotalPedidosEntregados() + 1);
                // Eliminar pedido de la lista de pedidos
                contexto.getPedidos().remove(pedido);
                camion.clearDesvio();
                camion.setStatus(CamionEstado.TruckStatus.AVAILABLE); // Vuelve a estar disponible
                
                camion.getPedidosCargados().removeIf(p -> p.getId() == pedido.getId());

                // Después de entregar, decide si sigue con su ruta o vuelve a la base
                if (!camion.getPedidosCargados().isEmpty() && camion.getStatus() != CamionEstado.TruckStatus.BREAKDOWN) {
                    Pedido siguiente = camion.getPedidosCargados().get(0);
                    List<Point> ruta = buildManhattanPath(
                            camion.getX(), camion.getY(),
                            siguiente.getX(), siguiente.getY(),
                            tiempoActual,
                            contexto
                    );
                    int tt = (int) Math.ceil(ruta.size() * (60.0/50.0));
                    camion.setRuta(ruta);
                    camion.setPasoActual(0);
                    camion.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    camion.setTiempoLibre(tiempoActual.plusMinutes(tt + TIEMPO_SERVICIO));
                    nuevosEventos.add(new EntregaEvent(tiempoActual.plusMinutes(tt), camion.getPlantilla().getId(), siguiente));
                } else {
                    startReturn(camion, tiempoActual, nuevosEventos, contexto);
                }
            } else {
                // continuar con la ruta pendiente
                if (!camion.getPedidosCargados().isEmpty() && camion.getStatus() != CamionEstado.TruckStatus.BREAKDOWN) {
                    Pedido siguiente = camion.getPedidosCargados().get(0);
                    List<Point> ruta = buildManhattanPath(
                            camion.getX(), camion.getY(),
                            siguiente.getX(), siguiente.getY(),
                            tiempoActual,
                            contexto
                    );
                    camion.setRuta(ruta);
                    camion.setPasoActual(0);
                    camion.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    //camion.getHistory().addAll(ruta);
                    int tt = (int) Math.ceil(ruta.size() * (60.0/50.0));
                    camion.setTiempoLibre(tiempoActual.plusMinutes(tt + TIEMPO_SERVICIO));
                    nuevosEventos.add(new EntregaEvent(tiempoActual.plusMinutes(tt), camion.getPlantilla().getId(), siguiente));
                } else {
                    startReturn(camion, tiempoActual, nuevosEventos, contexto);
                }
            }
        }

        // añadir todos los eventos recién creados
        contexto.getEventosEntrega().addAll(nuevosEventos);
    }

    // helper: inicia retorno y programa el evento de llegada
    private void startReturn(CamionEstado c, LocalDateTime tiempoActual, List<EntregaEvent> collector, ExecutionContext contexto) {
        double falta = c.getPlantilla().getCapacidadCarga() - c.getCapacidadDisponible();
        int sx = c.getX(), sy = c.getY();
        int dx = contexto.getDepositoX(), dy = contexto.getDepositoY();
        int distMin = Math.abs(sx - dx) + Math.abs(sy - dy);
        TanqueDinamico mejorT = null;
        for (TanqueDinamico t : contexto.getTanques()) {
            if (t.getDisponible() >= falta) {
                int d = Math.abs(sx - t.getPosX()) + Math.abs(sy - t.getPosY());
                if (d < distMin) { distMin = d; mejorT = t; }
            }
        }
        c.setReabastecerEnTanque(mejorT);
        int destX = mejorT != null ? mejorT.getPosX() : dx;
        int destY = mejorT != null ? mejorT.getPosY() : dy;
        if (mejorT != null) {
            mejorT.setDisponible(mejorT.getDisponible() - falta);
            /*System.out.printf(
                    "🔁 t+%d: Tanque (%d,%d) reservado %.1fm³ → ahora %.1f m³%n",
                    tiempoActual, mejorT.getPosX(), mejorT.getPosY(),
                    falta, mejorT.getDisponible()
            );*/
        }
        c.setStatus(CamionEstado.TruckStatus.RETURNING);

        List<Point> camino = buildManhattanPath(sx, sy, destX, destY, tiempoActual, contexto);
        c.setRuta(camino);
        c.setPasoActual(0);
        //c.getHistory().addAll(camino);

        /*System.out.printf(
                "⏱️ t+%d: Camión %s inicia retorno a (%d,%d) dist=%d%n",
                tiempoActual, c.getPlantilla().getId(), destX, destY, distMin
        );*/
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
        List<Point> pathToNew = buildManhattanPath(prevX, prevY, p.getX(), p.getY(), hora, contexto);
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
            List<Point> pathSeg = buildManhattanPath(prevX, prevY, orig.getX(), orig.getY(), hora, contexto);
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
                List<Point> path = buildManhattanPath(simX, simY, q.getX(), q.getY(), hora, contexto);
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
  private void aplicarRutas(LocalDateTime tiempoActual, List<Ruta> rutas, List<Pedido> activos, ExecutionContext contexto) {
        rutas.removeIf(r -> r.getPedidoIds() == null || r.getPedidoIds().isEmpty());
        if (rutas.isEmpty()) {
            /*System.out.printf("⚠ t+%d: ACO no encontró ruta válida, aplicando asignación secuencial para %s%n",
                    tiempoActual,
                    activos.stream().map(p -> "#" + p.getId()).collect(Collectors.joining(", "))
            );*/
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
                    List<Point> path = buildManhattanPath(mejor.getX(), mejor.getY(), p.getX(), p.getY(), tiempoActual, contexto);
                    mejor.setRuta(path);    
                    mejor.setPasoActual(0);
                    mejor.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    mejor.getPedidosCargados().add(p);
                    p.setProgramado(true);
                    int viaje = path.size();

                    contexto.getEventosEntrega().add(new EntregaEvent(tiempoActual.plusMinutes(viaje + TIEMPO_SERVICIO), mejor.getPlantilla().getId(), p));
                    p.setHoraEntregaProgramada(tiempoActual.plusMinutes(viaje + TIEMPO_SERVICIO));
                    /*System.out.printf("🔀 t+%d: Fallback – Pedido #%d asignado a %s, ruta de %d pasos%n",
                            tiempoActual, p.getId(), mejor.getPlantilla().getId(), viaje);*/
                } else {
                    /*System.out.printf("❌ t+%d: No hay camión disponible para Pedido #%d%n",
                            tiempoActual, p.getId());*/
                }
            }
            return;
        }

        // A) Filtrar rutas que no caben en la flota real
        for (Iterator<Ruta> itR = rutas.iterator(); itR.hasNext(); ) {
            Ruta r = itR.next();
            CamionEstado real = findCamion(r.getCamionId(), contexto);
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
                /*System.out.printf("⚠ t+%d: Ruta descartada para %s (no cabe volumen) → %s%n",
                        tiempoActual, real.getPlantilla().getId(),
                        r.getPedidoIds().stream().map(i -> activos.get(i).getId()).collect(Collectors.toList()));*/
                itR.remove();
            }
        }
        // B) Aplicar cada ruta al estado real
        for (Ruta ruta : rutas) {
            CamionEstado camion = findCamion(ruta.getCamionId(), contexto);

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
                        /*System.out.printf("⚠ t+%d: Camión %s NO tiene capacidad para Pedido #%d (restan=%.1f m³)%n",
                                tiempoActual, camion.getPlantilla().getId(), p.getId(), camion.getCapacidadDisponible());*/
                        continue;
                    }
                    if (!esDesvioValido(camion, p, tiempoActual, contexto)) continue;

                    // — reservo espacio —
                    camion.setCapacidadDisponible(camion.getCapacidadDisponible() - p.getVolumen());

                    // — construyo sólo el tramo de desvío y encolo en rutaPendiente —
                    List<Point> caminoDesvio = buildManhattanPath(
                            camion.getX(), camion.getY(),
                            p.getX(), p.getY(),
                            tiempoActual,
                            contexto
                    );
                    if (caminoDesvio != null && camion.getStatus() != CamionEstado.TruckStatus.BREAKDOWN) {
                        // 1) Reemplaza la ruta actual por el tramo de desvío
                        camion.getRutaActual().clear();
                        camion.getRutaActual().addAll(caminoDesvio);
                        camion.setPasoActual(0);

                        // 2) Programa el evento de entrega
                        // ── PARCHE: programar fin de servicio del pedido desviado ──
                        int ttDesvio = (int) Math.ceil(caminoDesvio.size() * (60.0 / 50.0));
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
                        calcularPuntosAveria(camion, contexto);
                        /*System.out.printf("🔀 t+%d: Pedido #%d asignado a Camión %s (desvío), cap restante=%.1f m³%n",
                                tiempoActual, p.getId(), camion.getPlantilla().getId(), camion.getCapacidadDisponible());*/
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
                if (!camion.getPedidosCargados().isEmpty() && camion.getStatus() != CamionEstado.TruckStatus.BREAKDOWN) {
                    List<Point> rutaCompleta = new ArrayList<>();
                    int cx = camion.getX(), cy = camion.getY();
                    for (Pedido p : camion.getPedidosCargados()) {
                        List<Point> seg = buildManhattanPath(cx, cy, p.getX(), p.getY(), tiempoActual, contexto);
                        rutaCompleta.addAll(seg);
                        cx = p.getX();
                        cy = p.getY();
                    }
                    camion.setRuta(rutaCompleta);
                    camion.setPasoActual(0);
                    camion.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    //camion.getHistory().addAll(rutaCompleta);

                    calcularPuntosAveria(camion, contexto);

                    // 3) Programar un EntregaEvent secuencial para cada pedido
                    LocalDateTime t = tiempoActual;
                    cx = camion.getX();
                    cy = camion.getY();
                    for (Pedido p : camion.getPedidosCargados()) {
                        int pasos = buildManhattanPath(cx, cy, p.getX(), p.getY(), t, contexto).size();
                        t = t.plusMinutes(pasos + TIEMPO_SERVICIO);
                        contexto.getEventosEntrega().add(new EntregaEvent(t, camion.getPlantilla().getId(), p));
                        p.setHoraEntregaProgramada(t);
                        cx = p.getX();
                        cy = p.getY();
                    }
                }
            }
        }

    }

    private CamionEstado findCamion(String camionId, ExecutionContext estado) {
        return estado.getCamiones().stream()
                .filter(c -> c.getPlantilla().getId().equals(camionId))
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------
    // 14) Conversor turno a partir de minuto (“T1”|“T2”|“T3”)
    // ------------------------------------------------------------
    private String turnoDeDateTime(LocalDateTime dateTime) {
        int hour = dateTime.getHour();
        int minute = dateTime.getMinute();
        int minutesOfDay = hour * 60 + minute;
        
        if (minutesOfDay < 480) return "T1"; // Antes de las 8:00
        else if (minutesOfDay < 960) return "T2"; // Entre 8:00 y 16:00
        else return "T3"; // Después de las 16:00
    }

    private boolean isBlockedMove(int x, int y, LocalDateTime t, ExecutionContext estado) {
    for (Bloqueo b : estado.getBloqueosPorDia()) { 
        if (b.isActiveAt(t) && b.estaBloqueado(t, new Point(x, y))) {
            return true;
        }
    }
    return false;
}

    private List<Point> findPathAStar(Point start, Point end, LocalDateTime startTime, ExecutionContext estado) {
        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingInt(node -> node.fCost));
        Set<Point> closedSet = new HashSet<>();
        Map<Point, Node> allNodes = new HashMap<>();

        Node startNode = new Node(start, null, 0, manhattanDistance(start, end));
        openSet.add(startNode);
        allNodes.put(start, startNode);

        while (!openSet.isEmpty()) {
            Node currentNode = openSet.poll();

            if (currentNode.position.equals(end)) {
                return reconstructPath(currentNode);
            }

            closedSet.add(currentNode.position);

            for (Point neighborPos : getNeighbors(currentNode.position)) {
                LocalDateTime tiempoLlegadaVecino = startTime.plusMinutes(currentNode.gCost + 1);

                // 1. ¿El vecino que estamos evaluando es el destino final?
                boolean esDestinoFinal = neighborPos.equals(end);
                
                // 2. ¿El nodo que estamos expandiendo es el punto de partida original de esta búsqueda?
                boolean enElPuntoDePartidaOriginal = currentNode.position.equals(start);

                // 3. El movimiento hacia el vecino está bloqueado si el vecino está bloqueado...
                boolean movimientoBloqueado = isBlockedMove(neighborPos.x, neighborPos.y, tiempoLlegadaVecino, estado) ||
                                            (isBlockedMove(currentNode.position.x, currentNode.position.y, tiempoLlegadaVecino, estado) && !enElPuntoDePartidaOriginal);

                // 4. La condición final completa para ignorar un vecino:
                if (closedSet.contains(neighborPos) || (movimientoBloqueado && !esDestinoFinal)) {
                    continue;
                }

                int tentativeGCost = currentNode.gCost + 1;
                Node neighborNode = allNodes.getOrDefault(neighborPos, new Node(neighborPos));

                if (tentativeGCost < neighborNode.gCost) {
                    neighborNode.parent = currentNode;
                    neighborNode.gCost = tentativeGCost;
                    neighborNode.hCost = manhattanDistance(neighborPos, end);
                    neighborNode.fCost = neighborNode.gCost + neighborNode.hCost;
                    
                    if (!openSet.contains(neighborNode)) {
                        openSet.add(neighborNode);
                    }
                    allNodes.put(neighborPos, neighborNode);
                }
            }
        }
        return null; // No path found
    }

    private int manhattanDistance(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    /**
     * Construye una ruta Manhattan entre dos puntos, teniendo en cuenta bloqueos.
     * Si hay un bloqueo, recurre al algoritmo A* para encontrar una ruta alternativa.
     * 
     * @param x1 Coordenada x del punto inicial
     * @param y1 Coordenada y del punto inicial
     * @param x2 Coordenada x del punto destino
     * @param y2 Coordenada y del punto destino
     * @param tiempoInicial Tiempo en el que se inicia el recorrido
     * @param estado Contexto de ejecución con información de bloqueos
     * @return Lista de puntos que forman la ruta
     */
    public List<Point> buildManhattanPath(int x1, int y1, int x2, int y2, LocalDateTime tiempoInicial, ExecutionContext estado) {
        List<Point> path = new ArrayList<>();
        /*Point current = new Point(x1, y1);
        LocalDateTime t = tiempoInicial;

        while (current.x != x2 || current.y != y2) {
            Point prev = new Point(current.x, current.y);
            if      (current.x < x2) current.x++;
            else if (current.x > x2) current.x--;
            else if (current.y < y2) current.y++;
            else                     current.y--;

            Point next = new Point(current.x, current.y);
            LocalDateTime tiempoLlegada = t.plusMinutes(1);

            if (isBlockedMove(next.x, next.y, tiempoLlegada, estado)) {
                // Invocar A* si hay bloqueo
                List<Point> alt = findPathAStar(prev, new Point(x2, y2), tiempoLlegada, estado);
                if (alt == null || alt.isEmpty()) {
                    System.err.printf("Error: No hay ruta de (%d,%d) a (%d,%d) en %s debido a bloqueos%n", 
                                    x1, y1, x2, y2, tiempoInicial);
                    return Collections.emptyList();
                }
                path.addAll(alt);
                return path;
            }
            path.add(next);
            t = tiempoLlegada;
        }*/
        path = findPathAStar(new Point(x1, y1), new Point(x2, y2), tiempoInicial, estado);
        if (path == null || path.isEmpty()) {
            System.err.printf("Error: No hay ruta de (%d,%d) a (%d,%d) en %s debido a bloqueos%n", 
                    x1, y1, x2, y2, tiempoInicial);
            return Collections.emptyList();
        }
        return path;
    }

    private List<Point> getNeighbors(Point p) {
        List<Point> neighbors = new ArrayList<>();
        neighbors.add(new Point(p.x + 1, p.y));
        neighbors.add(new Point(p.x - 1, p.y));
        neighbors.add(new Point(p.x, p.y + 1));
        neighbors.add(new Point(p.x, p.y - 1));
        return neighbors;
    }

    private List<Point> reconstructPath(Node endNode) {
        LinkedList<Point> path = new LinkedList<>();
        Node currentNode = endNode;
        while (currentNode != null) {
            path.addFirst(currentNode.position);
            currentNode = currentNode.parent;
        }
        path.removeFirst(); 
        return path;
    }

    /**
     * Actualiza los bloqueos activos en el contexto para el tiempo actual.
     * Publica eventos de actualización de bloqueo si hay cambios.
     * 
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     * @param simulationId El ID de la simulación
     */
    private void actualizarBloqueosActivos(ExecutionContext contexto, LocalDateTime tiempoActual) {
        List<Bloqueo> bloqueosQueInicianAhora = contexto.getBloqueosPorTiempo().remove(tiempoActual);
        if (bloqueosQueInicianAhora != null) {
            for (Bloqueo b : bloqueosQueInicianAhora) {
                contexto.addBloqueoActivo(b);
                b.setLastKnownState(Bloqueo.Estado.ACTIVO);
                System.out.printf("🚧 Bloqueo activado: %s%n", b.getDescription());
            }
        }

        // 2. Revisar la lista de activos (que siempre es pequeña) para desactivar los que terminaron
        // Esta parte de tu lógica ya era eficiente y se mantiene.
        List<Bloqueo> bloqueosActivos = new ArrayList<>(contexto.getBloqueosActivos());
        for (Bloqueo b : bloqueosActivos) {
            if (!b.isActiveAt(tiempoActual)) {
                if (b.getLastKnownState() == Bloqueo.Estado.ACTIVO) {
                    contexto.removeBloqueoActivo(b);
                    b.setLastKnownState(Bloqueo.Estado.TERMINADO);
                    System.out.printf("✅ Bloqueo finalizado: %s%n", b.getDescription());
                }
            }
        }
    }
    
    private boolean procesarAverias(ExecutionContext contexto, LocalDateTime tiempoActual) {
        boolean replanificar = false;
        
        // Determinar el turno actual
        String turnoActual = turnoDeDateTime(tiempoActual);
        
        // Si cambió el turno, limpiar estados de averías anteriores
        if (!turnoActual.equals(contexto.getTurnoAnterior())) {
            contexto.setTurnoAnterior(turnoActual);
            //contexto.getAveriasAplicadas().clear();
            
        }
        // Aplicar averías programadas para este turno
        Map<String, Averia> averiasTurno = contexto.getAveriasPorTurno().getOrDefault(turnoActual, Collections.emptyMap());
        for (Map.Entry<String, Averia> entry : averiasTurno.entrySet()) {
            String key = turnoActual + "_" + entry.getKey();
            if (contexto.getAveriasAplicadas().contains(key)) continue;   
            CamionEstado c = findCamion(entry.getKey(), contexto);
            Averia datoaveria = entry.getValue();
            // Para averías cargadas desde archivo
            if (datoaveria.isFromFile()) {  
                // Solo aplicar si el camión está entregando y tiene una ruta asignada
                if (c != null && c.getStatus() == CamionEstado.TruckStatus.DELIVERING && 
                    c.getRutaActual() != null && !c.getRutaActual().isEmpty()) {         
                    Integer puntoAveria = contexto.getPuntosAveria().get(entry.getKey());
                    // Solo aplicar si hay un punto de avería calculado y el camión está en ese punto
                    if (puntoAveria != null && c.getPasoActual() == puntoAveria) {
                        System.out.println("<<<<<<<<<<< entro a procesar averias >>>>>>>>>>"); 
                        System.out.println("🔍 Camión " + c.getPlantilla().getId() + 
                                         " llegó al punto de avería calculado (paso " + puntoAveria + 
                                         " de " + c.getRutaActual().size() + ")");
                        
                        if (aplicarAveria(c, datoaveria, tiempoActual, turnoActual, contexto, key)) {
                            replanificar = true;
                            // Eliminar el punto de avería usado para permitir futuras averías.
                            contexto.getPuntosAveria().remove(entry.getKey());
                        }
                    }
                }
            } else {

                if (c != null && (c.getTiempoLibreAveria() == null || !c.getTiempoLibreAveria().isAfter(tiempoActual))){

                    if (aplicarAveria(c, datoaveria, tiempoActual, turnoActual, contexto, key)) {
                        replanificar = true;
                    }
                }
            }

        }
        
        // Revisar camiones que ya pueden volver a servicio
        Iterator<String> it = contexto.getCamionesInhabilitados().iterator();
        while (it.hasNext()) {
            String camionId = it.next();
            CamionEstado c = findCamion(camionId, contexto);
            String tipoAveria = c.getTipoAveriaActual();
            // Verificamos si el camión ya ha cumplido su tiempo de inmovilización
            // Calcular y mostrar el tiempo transcurrido desde que se declaró la avería
            long minutosTranscurridos = java.time.Duration.between(c.getTiempoInicioAveria(), tiempoActual).toMinutes();
            
            // Solo teleportar si se ha cumplido el tiempo de inmovilización y no está en taller
            if (tipoAveria != null && !c.isEnTaller() && 
                ((tipoAveria.equals("T2") && minutosTranscurridos > 120) || 
                 (tipoAveria.equals("T3") && minutosTranscurridos > 240))) {
                
                // Para averías T2 y T3, teleportar a posición de origen
                c.setX(c.getPlantilla().getInitialX());
                c.setY(c.getPlantilla().getInitialY());
                // Marcar como en taller para evitar teleportaciones repetidas
                c.setEnTaller(true);
                
                System.out.printf("🚚 Camión %s TELEPORTADO a origen (%d,%d) tras finalizar exactamente avería tipo %s en %s%n", 
                                c.getPlantilla().getId(), c.getX(), c.getY(), tipoAveria, tiempoActual);
            }

            // Si el camión está disponible o su tiempo libre ha expirado
            if (c != null && (c.getTiempoLibreAveria() == null || !c.getTiempoLibreAveria().isAfter(tiempoActual))) {
                it.remove();
                // Restaurar estado del camión tras reparación
                // Reiniciar estado de taller para futuras averías
                c.setEnTaller(false);
                c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                c.setTiempoLibreAveria(null);
                c.setRuta(java.util.Collections.emptyList());
                c.setPasoActual(0);
                c.setCapacidadDisponible(c.getPlantilla().getCapacidadCarga());
                System.out.printf("🚚 Camión %s reparado y disponible nuevamente en %s%n", 
                                c.getPlantilla().getId(), tiempoActual);
                // Limpiar el tipo de avería ya que el camión está reparado
                
                c.setTipoAveriaActual(null);
                replanificar = true;
            }
        }
        
        return replanificar;
    }

    
    public static int calcularTiempoAveria(String turnoActual, String tipoIncidente  ,int tiempoActual) {

        int inactividad = 0;
        int minutosEnTurno = tiempoActual % 480; // Minutos dentro del turno actual
        int minutosHastaFinTurno = 480 - minutosEnTurno; // Minutos hasta fin de turno

        switch (tipoIncidente) {
            case "T1":
                // Tipo 1: 2 horas en sitio (120 minutos)
                inactividad = 120;
                break;

            case "T2":
                // Tipo 2: 2 horas en sitio + tiempo variable según turno
                inactividad = 120; // Inmovilización inicial de 2 horas

                switch (turnoActual) {
                    case "T1":
                        // Disponible en turno 3 del mismo día (saltar turno 2 completo)
                        inactividad += minutosHastaFinTurno; // Resto del turno 1
                        inactividad += 480; // Turno 2 completo
                        break;
                    case "T2":
                        // Disponible en turno 1 del día siguiente
                        inactividad += minutosHastaFinTurno; // Resto del turno 2
                        inactividad += 480; // Turno 3 completo
                        break;
                    case "T3":
                        // Disponible en turno 2 del día siguiente
                        inactividad += minutosHastaFinTurno; // Resto del turno 3
                        inactividad += 480; // Turno 1 del día siguiente completo
                        break;
                }
                break;

            case "T3":
                // Tipo 3: 4 horas en sitio + tiempo hasta T1 del día A+3
                inactividad = 240; // Inmovilización inicial de 4 horas
                
                // Calcula minutos desde hora actual hasta las 00:00 del día A+3
                // Primero, minutos restantes del día actual
                int minutosRestantesDiaActual = 1440 - (tiempoActual % 1440);
                // Más un día completo (día A+1 a A+2)
                int minutosHastaDiaA3 = minutosRestantesDiaActual + 1440;
                // Más 0 minutos del día A+3 (ya estamos al inicio del día)
                inactividad += minutosHastaDiaA3;
                break;

            default:
                System.out.println("Tipo de incidente desconocido: " + tipoIncidente);
                break;
        }

        return inactividad;
    }

    private void calcularPuntosAveria(CamionEstado camion, ExecutionContext contexto) {
        String idCamion = camion.getPlantilla().getId();
        int totalPasos = camion.getRutaActual().size();
        
        // Si la ruta es muy corta, no calculamos puntos de avería
        if (totalPasos < 5) return;
    
        // Recorrer el mapa de averías por turno (T1, T2, T3)
        for (Map.Entry<String, Map<String, Averia>> entryTurno : contexto.getAveriasPorTurno().entrySet()) {
            Map<String, Averia> averiasPorCamion = entryTurno.getValue();
            
            // Verificar si hay una avería para este camión en este turno
            if (averiasPorCamion.containsKey(idCamion)) {
                Averia averia = averiasPorCamion.get(idCamion);
                
                // Solo procesamos averías cargadas desde archivo
                if (averia.isFromFile()) {
                    if (contexto.getPuntosAveria().containsKey(idCamion)) continue;
                    // Calcular punto aleatorio para esta avería
                    Random random = new Random();
                    int pasoMinimo = Math.max(1, (int) (totalPasos * 0.05)); // 5% de la ruta
                    int pasoMaximo = Math.max(pasoMinimo + 1, (int) (totalPasos * 0.35)); // 35% de la ruta
                    int pasoAveria = pasoMinimo + random.nextInt(pasoMaximo - pasoMinimo + 1);
                    
                    // Guardar el punto de avería en el contexto
                    contexto.getPuntosAveria().put(idCamion, pasoAveria);
                }
            }
        }
    }
    
    private boolean aplicarAveria(CamionEstado camion, Averia averia, LocalDateTime tiempoActual, 
                            String turnoActual, ExecutionContext contexto, String key) {
    // Determinar penalización según tipo de avería
        int minutosActuales = tiempoActual.getHour() * 60 + tiempoActual.getMinute();
        int penal = calcularTiempoAveria(turnoActual, averia.getTipoIncidente(), minutosActuales);           
        camion.setTiempoLibreAveria(tiempoActual.plusMinutes(penal));
        camion.setTiempoInicioAveria(tiempoActual); // Guardar cuándo inicia la avería
        camion.setStatus(CamionEstado.TruckStatus.BREAKDOWN);
        // Guardar el tipo de avería en el camión para usarlo después
        camion.setTipoAveriaActual(averia.getTipoIncidente());
        // Reiniciar estado de taller para permitir teleportación si es necesario
        camion.setEnTaller(false);

        // --- Acciones inmediatas por avería ---
        // Remover eventos de entrega pendientes para este camión
       // removerEventosEntregaDeCamion(camion.getPlantilla().getId(), contexto);
        // Liberar pedidos pendientes y limpiar ruta
        for (Pedido pPend : new ArrayList<>(camion.getPedidosCargados())) {
            pPend.setProgramado(false); // volver a la cola de planificación
            pPend.setHoraEntregaProgramada(null);
            pPend.setAtendido(false); 
            System.out.println("🔴 Pedido " + pPend.getId() + " liberado por avería del camión " + camion.getPlantilla().getId());
        }
        // Limpieza total de datos de rutas y pedidos para el camión averiado
        camion.getPedidosCargados().clear();  // Limpiar pedidos cargados
        camion.setRuta(Collections.emptyList());  // Limpiar ruta actual
        camion.setPasoActual(0);  // Resetear paso actual
        camion.getHistory().clear();  // Limpiar historial de movimientos

        // Restaurar capacidad total del camión (queda vacío tras trasvase virtual)
        camion.setCapacidadDisponible(camion.getPlantilla().getCapacidadCarga());
        contexto.getCamionesInhabilitados().add(camion.getPlantilla().getId());

        // Remover cualquier evento de entrega pendiente para este camión
        removerEventosEntregaDeCamion(camion.getPlantilla().getId(), contexto);

        // Marcar la avería como aplicada
        contexto.getAveriasAplicadas().add(key);
        
        System.out.println("🔧 Avería tipo " + averia.getTipoIncidente() + 
                        " aplicada al camión " + camion.getPlantilla().getId() + 
                        " en " + tiempoActual + 
                        ". Tiempo estimado de reparación: " + penal + " minutos.");
        
        return true; // Siempre replanificar después de una avería
    }
    /**
     * Procesa los eventos de entrega que ocurren en el tiempo actual.
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     */
    /**
     * Remueve todos los eventos de entrega pendientes para un camión específico.
     */
    private void removerEventosEntregaDeCamion(String camionId, ExecutionContext contexto) {
        Iterator<EntregaEvent> itEv = contexto.getEventosEntrega().iterator();
        while (itEv.hasNext()) {
            EntregaEvent ev = itEv.next();
            if (ev.getCamionId().equals(camionId)) {
                if (ev.getPedido() != null) {
                ev.getPedido().setProgramado(false); // Liberar el pedido
            }
                System.out.printf("❌ Evento de entrega eliminado por avería: Pedido %s de camión %s%n",
                        ev.getPedido().getId(), camionId);
                itEv.remove();
            }
        }
    }

}