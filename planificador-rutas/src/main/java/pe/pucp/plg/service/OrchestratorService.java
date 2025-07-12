package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.dto.*;
import pe.pucp.plg.dto.enums.EventType;
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
    private static final int INTERVALO_REPLAN = 20;
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
            // 0) Está descargando/recargando => no avanza
            if (c.getStatus() == CamionEstado.TruckStatus.UNAVAILABLE){
                /*System.out.printf("⏱️ t+%d: Camión %s en servicio, libre en t+%d%n",
                        tiempoActual, c.getPlantilla().getId(), c.getTiempoLibre());*/
                continue;
            }

            // 2) Retorno a planta
            if (c.getStatus() == CamionEstado.TruckStatus.RETURNING) {
                if (c.tienePasosPendientes()) {
                    c.avanzarUnPaso();
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
                c.avanzarUnPaso();
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
            if (!p.isAtendido() && !p.isDescartado() && tiempoActual.isAfter(p.getTiempoLimite())) {
                /*System.out.printf("💥 Colapso en t+%d, pedido %d incumplido%n",
                        tiempoActual, p.getId());*/
                // Marca y elimina para no repetir el colapso
                p.setDescartado(true);
                itP.remove();
                haColapsado = true;
            }
        }

        // COLAPSO
        if(haColapsado && !contexto.isIgnorarColapso()) {
            // Si ha colapsado y no se ignora, destruir la simulacion
            System.out.printf("💥 Colapso detectado en t+%d, finalizando simulación%n", tiempoActual);
            eventPublisher.publicarEventoSimulacion(simulationId, EventDTO.of(EventType.SIMULATION_COLLAPSED, null));
            return null;
        }

        // 7) Averías por turno (T1, T2, T3)
        String turnoActual = turnoDeDateTime(tiempoActual);
        if (!turnoActual.equals(contexto.getTurnoAnterior())) {
            contexto.setTurnoAnterior(turnoActual);
            contexto.getAveriasAplicadas().clear();
            contexto.getCamionesInhabilitados().clear();
        }
        Map<String, String> averiasTurno = contexto.getAveriasPorTurno()
                .getOrDefault(turnoActual, Collections.emptyMap());
        List<String> keysAProcesar = new ArrayList<>(averiasTurno.keySet());
        for (String mid : keysAProcesar) {
            String key = turnoActual + "_" + mid;
            if (contexto.getAveriasAplicadas().contains(key)) continue;
            CamionEstado c = findCamion(mid, contexto);
            if (c != null && c.getTiempoLibre() != null && !c.getTiempoLibre().isAfter(tiempoActual)) {
                String tipo = averiasTurno.get(mid);
                int penal = tipo.equals("T1") ? 30 : tipo.equals("T2") ? 60 : 90;
                c.setTiempoLibre(tiempoActual.plusMinutes(penal));
                contexto.getAveriasAplicadas().add(key);
                contexto.getCamionesInhabilitados().add(c.getPlantilla().getId());
                replanificar = true;
                /*System.out.printf("🚨 t+%d: Camión %s sufre avería tipo %s, penal=%d%n",
                        tiempoActual, c.getPlantilla().getId(), tipo, penal);*/
            }
        }
        // limpiar inhabilitados
        Iterator<String> itInh = contexto.getCamionesInhabilitados().iterator();
        while (itInh.hasNext()) {
            CamionEstado c = findCamion(itInh.next(), contexto);
            if (c != null && c.getTiempoLibre() != null && !c.getTiempoLibre().isAfter(tiempoActual)) {
                itInh.remove();
                replanificar = true;
            }
        }

        // 8) Construir estado “ligero” de la flota disponible para ACO
        List<CamionEstado> flotaEstado = contexto.getCamiones().stream()
                .filter(c -> c.getStatus() != CamionEstado.TruckStatus.UNAVAILABLE
                        && c.getPedidosCargados().isEmpty()            // no tiene entregas encoladas
                        && c.getPedidoDesvio() == null)               // no está en medio de un desvío
                .map(c -> {
                    CamionEstado est = new CamionEstado(c);
                    return est;
                })
                .collect(Collectors.toList());

        if (replanificar && flotaEstado.isEmpty()) {
            /*System.out.printf("⏲️ t+%d: Ningún camión disponible (ni en ventana) → replanificación pospuesta%n",
                    tiempoActual);*/
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

            // B) Desvío local con búsqueda del mejor camión
            List<Pedido> sinAsignar = new ArrayList<>();
            for (Pedido p : candidatos) {
                CamionEstado mejor = null;
                int mejorDist = Integer.MAX_VALUE;
                // Encuentra el mejor camión para desvío
                for (CamionEstado c : contexto.getCamiones()) {
                    if (c.getStatus() == CamionEstado.TruckStatus.UNAVAILABLE) continue;
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
                    else {
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

            // CASO A: El evento es un retorno a la planta (no hay pedido)
            if (pedido == null) {
                startReturn(camion, tiempoActual, nuevosEventos, contexto);
                continue;
            }

            // CASO B: El evento es una LLEGADA al cliente
            if (camion.getStatus() == CamionEstado.TruckStatus.DELIVERING) {
                System.out.printf("➡️  LLEGADA: Camión %s llegó a pedido %d en %s. Inicia servicio.%n", camion.getPlantilla().getId(), pedido.getId(), tiempoActual);
                
                camion.setX(pedido.getX());
                camion.setY(pedido.getY());
                camion.setStatus(CamionEstado.TruckStatus.UNAVAILABLE); // Ocupado durante el servicio
                
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
                // Eliminar pedido de la lista de pedidos
                contexto.getPedidos().remove(pedido);
                camion.setStatus(CamionEstado.TruckStatus.AVAILABLE); // Vuelve a estar disponible
                
                camion.getPedidosCargados().removeIf(p -> p.getId() == pedido.getId());

                // Después de entregar, decide si sigue con su ruta o vuelve a la base
                if (!camion.getPedidosCargados().isEmpty()) {
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
                if (!camion.getPedidosCargados().isEmpty()) {
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
                    if (caminoDesvio != null) {
                        // 1) Reemplaza la ruta actual por el tramo de desvío
                        camion.getRutaActual().clear();
                        camion.getRutaActual().addAll(caminoDesvio);
                        camion.setPasoActual(0);

                        // 2) Programa el evento de entrega
                        // ── PARCHE: programar fin de servicio del pedido desviado ──
                        int ttDesvio = (int) Math.ceil(caminoDesvio.size() * (60.0 / 50.0));
                        LocalDateTime finServicio = tiempoActual.plusMinutes(ttDesvio + TIEMPO_SERVICIO);
                        camion.setStatus(CamionEstado.TruckStatus.DELIVERING);
                        camion.setTiempoLibre(finServicio);
                        contexto.getEventosEntrega().add(
                            new EntregaEvent(finServicio, camion.getPlantilla().getId(), p)  // p = pedidoDesvio
                            );
                        p.setHoraEntregaProgramada(finServicio);
                        // ────────────────────────────────────────────────────────────────────────────
                        p.setProgramado(true);
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
                if (!camion.getPedidosCargados().isEmpty()) {
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

                if (closedSet.contains(neighborPos) || 
                    isBlockedMove(neighborPos.x, neighborPos.y, tiempoLlegadaVecino, estado) ||
                    isBlockedMove(currentNode.position.x, currentNode.position.y, tiempoLlegadaVecino, estado)) {
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
}
