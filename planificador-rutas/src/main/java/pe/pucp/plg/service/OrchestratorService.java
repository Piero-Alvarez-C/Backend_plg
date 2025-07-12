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
import pe.pucp.plg.model.state.CamionEstado.TruckStatus;
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
    private final SimulationManagerService simulationManagerService;

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
    public OrchestratorService(EventPublisherService eventPublisher, SimulationManagerService simulationManagerService) {
        this.acoPlanner = new ACOPlanner();
        this.eventPublisher = eventPublisher;
        this.simulationManagerService = simulationManagerService;
    }

    /**
     * Advances the simulation identified by simulationId by one minute.
     * @param simulationId The ID of the simulation to step forward.
     * @return The new current time of the simulation.
     */
    public LocalDateTime stepOneMinute(String simulationId) {
        // Obtener el contexto de ejecución
        ExecutionContext contexto = simulationManagerService.getActiveSimulationContext();
        if (contexto == null) {
            if ("operational".equals(simulationId)) {
                contexto = simulationManagerService.getOperationalContext();
            } else {
                throw new IllegalArgumentException("Simulation context not found for ID: " + simulationId);
            }
        }

        // AVANZAR EL TIEMPO
        LocalDateTime tiempoActual = contexto.getCurrentTime() != null ?
                contexto.getCurrentTime().plusMinutes(1) : LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        contexto.setCurrentTime(tiempoActual);
        
        // Actualizar los bloqueos activos en este tiempo
        
        // Para verificaciones que necesitan un día nuevo
        boolean esMediaNoche = tiempoActual.getHour() == 0 && tiempoActual.getMinute() == 0 && !tiempoActual.equals(contexto.getFechaInicio().atStartOfDay());
        boolean replanificar = tiempoActual.equals(contexto.getFechaInicio().atStartOfDay()); // Replanificar al inicio siempre
        
        // 1. Recargar tanques (cada 24 horas) y cargar nuevos pedidos y bloqueos para el siguiente día
        if (esMediaNoche) {
            System.out.println("⛽ Recarga diaria de tanques en " + tiempoActual);
            for (TanqueDinamico tq : contexto.getTanques()) {
                tq.setDisponible(tq.getCapacidadTotal());
            }
            
            // Determinar qué día estamos y cargar datos para ese día
            LocalDate fechaActual = tiempoActual.toLocalDate();
            long diaActual = fechaActual.toEpochDay() - contexto.getFechaInicio().toEpochDay() + 1;
            System.out.println("📅 Día " + diaActual + " de la simulación, cargando nuevos datos...");
            
            // Solo cargamos datos nuevos si estamos dentro del período de simulación
            if (diaActual <= contexto.getDuracionDias()) {
                System.out.println("📅 Cargando datos para el día: " + fechaActual);
                
                // Cargar pedidos y bloqueos para este día
                List<Pedido> nuevoPedidos = ResourceLoader.cargarPedidosParaFecha(fechaActual);
                List<Bloqueo> nuevoBloqueos = ResourceLoader.cargarBloqueosParaFecha(fechaActual);

                System.out.printf("🔄 Día %d: Hay %d nuevos pedidos y %d bloqueos%n", 
                        diaActual, nuevoPedidos.size(), nuevoBloqueos.size());

                // Añadir los nuevos pedidos al mapa de pedidos por tiempo
                for (Pedido p : nuevoPedidos) {
                    contexto.getPedidosPorTiempo().computeIfAbsent(p.getTiempoCreacion(), k -> new ArrayList<>()).add(p);
                }
                
                // Añadir los nuevos bloqueos
                for (Bloqueo b : nuevoBloqueos) {
                    System.out.printf("🔒 Nuevo bloqueo: %s desde %s hasta %s con nodos: ", 
                            b.getDescription(), b.getStartTime(), b.getEndTime());
                    for(Point p : b.getNodes()) {
                        System.out.printf("(%d,%d) ", p.x, p.y);
                    }
                    System.out.printf("%n");
                    contexto.addBloqueo(b);
                }
                
                System.out.printf("🔄 Día %d: Cargados %d nuevos pedidos y %d bloqueos%n", 
                        diaActual, contexto.getPedidos().size(), contexto.getBloqueos().size());
                
                // Si hay nuevos datos, replanificar
                if (!nuevoPedidos.isEmpty() || !nuevoBloqueos.isEmpty()) {
                    replanificar = true;
                }
            }
        }

        actualizarBloqueosActivos(contexto, tiempoActual, simulationId);
        
        // 2. Procesar eventos de entrega programados
        procesarEventosEntrega(contexto, tiempoActual, simulationId);

        // 3. Iniciar retorno de camiones
        procesarRetorno(contexto, tiempoActual, simulationId);

        // 4. Avanzar cada camión en sus rutas actuales
        for (CamionEstado c : contexto.getCamiones()) {
            // Los camiones averiados NO deben avanzar en sus rutas
            /*if (c.getStatus() == CamionEstado.TruckStatus.BREAKDOWN) {
                // Omitir este camión - está averiado y no debe moverse
                continue;
            }*/ 
            
            if (c.tienePasosPendientes()) {
                c.avanzarUnPaso();
            } else if(c.getStatus() == CamionEstado.TruckStatus.RETURNING) {
                // Camión ha llegado al final de su ruta de retorno
                c.setCapacidadDisponible(c.getPlantilla().getCapacidadCarga());
                c.setCombustibleDisponible(c.getPlantilla().getCapacidadCombustible());
                c.setEnRetorno(false);
                c.setReabastecerEnTanque(null);
                c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                c.setTiempoLibre(tiempoActual.plusMinutes(15)); // Tiempo de descarga/recarga
                
                System.out.printf("🚚 Camión %s ha completado su retorno y está disponible en %s%n", 
                                 c.getPlantilla().getId(), tiempoActual.plusMinutes(15));
            }
        }
        
        // 5. Incorporar nuevos pedidos que llegan en este tiempo
        List<Pedido> nuevos = contexto.getPedidosPorTiempo().remove(tiempoActual);
        if (nuevos == null) {
            nuevos = Collections.emptyList();
        }         
        
        // 5.a Calcular capacidad máxima de un camión (suponiendo que todos tienen la misma capacidad)
        double capacidadMaxCamion = contexto.getCamiones().stream()
                .mapToDouble(CamionEstado::getCapacidadDisponible)   // o getDisponible() si prefieres la disponible inicial
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
                pedidosAInyectar.add(p);
            }
        }

        // 5.b) Añadir realmente los pedidos (reemplazo de los nuevos originales)
        contexto.getPedidos().addAll(pedidosAInyectar);

        for (Pedido p : pedidosAInyectar) {
            System.out.printf("🆕 %s: Pedido #%d recibido (destino=(%d,%d), vol=%.1fm³, límite %s)%n",
                    tiempoActual, p.getId(), p.getX(), p.getY(), p.getVolumen(), p.getTiempoLimite());
        }
        if (!pedidosAInyectar.isEmpty()) replanificar = true;

        // 6. Comprobar colapso
        Iterator<Pedido> itP = contexto.getPedidos().iterator();
        while (itP.hasNext()) {
            Pedido p = itP.next();
            if (!p.isAtendido() && !p.isDescartado() && tiempoActual.isAfter(p.getTiempoLimite())) {
                // Emitir evento SIMULATION_COLLAPSED con pedido afectado
                PedidoDTO pedidoDTO = MapperUtil.toPedidoDTO(p);
                EventDTO eventoColapso = EventDTO.of(EventType.SIMULATION_COLLAPSED, pedidoDTO);
                eventPublisher.publicarEventoSimulacion(simulationId, eventoColapso);

                System.out.printf("💥 Colapso en %s, pedido %d incumplido%n",
                        tiempoActual, p.getId());
                // Marca y elimina para no repetir el colapso
                p.setDescartado(true);
                itP.remove();
            }
        }
        
        // 7. Procesar averías por turno
        replanificar |= procesarAverias(contexto, tiempoActual);
        
        // 8. Preparar el ACO
        List<CamionEstado> flotaEstado = contexto.getCamiones().stream()
                .filter(c -> c.getStatus() == CamionEstado.TruckStatus.AVAILABLE)
                .map(c -> new CamionEstado(c))
                .collect(Collectors.toList());

        // 9) Determinar candidatos a replanificar
        Map<Pedido, LocalDateTime> entregaActual = new HashMap<>();
        for (EntregaEvent ev : contexto.getEventosEntrega()) {
            entregaActual.put(ev.getPedido(), ev.time);
        }
        List<Pedido> pendientes = contexto.getPedidos().stream()
                .filter(p -> !p.isAtendido() && !p.isDescartado() && !p.isProgramado() && !p.getTiempoCreacion().isAfter(tiempoActual))
                .collect(Collectors.toList());

        List<Pedido> candidatos = new ArrayList<>();
        for (Pedido p : pendientes) {
            if (tiempoActual.plusMinutes(60).isAfter(p.getTiempoLimite())) {
                candidatos.add(p);
                continue;
            }
            LocalDateTime tPrev = entregaActual.get(p);
            if (tPrev == null) {
                candidatos.add(p);
            } else {
                LocalDateTime mejorAlt = tPrev;
                for (CamionEstado est : flotaEstado) {
                    if (est.getCapacidadDisponible() < p.getVolumen()) continue;
                    int dt = Math.abs(est.getX() - p.getX()) + Math.abs(est.getY() - p.getY());
                    LocalDateTime llegada = tiempoActual.plusMinutes(dt);
                    if (llegada.isBefore(mejorAlt)) mejorAlt = llegada;
                }
                if (mejorAlt.isBefore(tPrev)) candidatos.add(p);
            }
        }
        candidatos.removeIf(p -> {
            LocalDateTime entregaMin = entregaActual.get(p);
            return entregaMin != null && entregaMin.isAfter(tiempoActual) && 
                   entregaMin.isBefore(tiempoActual.plusMinutes(2)); // 1 minute margin
        });
        
        // 9. Replanificar rutas si es necesario
        
        // Si hay nuevos pedidos, averías resueltas, o es inicio de simulación
        if (replanificar) {
            System.out.println("🔄 Ejecutando replanificación en " + tiempoActual);
            
            // 4.1 Solicitar al ACOPlanner que calcule nuevas rutas óptimas
            // Usamos directamente el LocalDateTime actual
            List<Ruta> nuevasRutas = acoPlanner.planificarRutas(candidatos, flotaEstado, tiempoActual, contexto);
            
            // 4.2 Traducir el plan a acciones concretas sobre el estado real
            aplicarRutas(tiempoActual, nuevasRutas, candidatos, contexto, simulationId);
            

            //4.2 b) Generar puntos averias para camiones
            generarPuntosAverias(contexto);
            // 4.3 Guardar las rutas en el contexto para visualización o análisis
            contexto.setRutas(nuevasRutas);
        }

        // Mandar por socket un snapshot del estado actual
        EventDTO estadoActual = EventDTO.of(EventType.SNAPSHOT, MapperUtil.toSnapshotDTO(contexto));
        eventPublisher.publicarEventoSimulacion(simulationId, estadoActual);
        return contexto.getCurrentTime();
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
                ev.getPedido().setProgramado(false); // Liberar el pedido
                System.out.printf("❌ Evento de entrega eliminado por avería: Pedido %s de camión %s%n",
                        ev.getPedido().getId(), camionId);
                itEv.remove();
            }
        }
    }

    private void procesarEventosEntrega(ExecutionContext contexto, LocalDateTime tiempoActual, String simulationId) {
        Iterator<EntregaEvent> itEv = contexto.getEventosEntrega().iterator();
        while (itEv.hasNext()) {
            EntregaEvent ev = itEv.next();
            if (ev.time.equals(tiempoActual)) {
                CamionEstado camion = findCamion(ev.getCamionId(), contexto);
                if (camion != null) {
                    // Verificar que el camión NO esté averiado
                    if (camion.getStatus() == CamionEstado.TruckStatus.BREAKDOWN) {
                        // NO procesar entregas para camiones averiados
                        System.out.printf("⚠️ %s: Evento de entrega ignorado para camión %s (AVERIADO) → Pedido %s%n",
                                tiempoActual, camion.getPlantilla().getId(), ev.getPedido().getId());
                        
                        // Reprogramar el pedido (ponerlo como no programado para que pueda ser asignado nuevamente)
                        ev.getPedido().setProgramado(false);
                        
                        // Eliminar el evento sin procesar la entrega
                        itEv.remove();
                        continue;
                    }
                    
                    // Proceder con la entrega normal para camiones no averiados
                    camion.setX(ev.getPedido().getX());
                    camion.setY(ev.getPedido().getY());
                    camion.setTiempoLibre(tiempoActual.plusMinutes(15)); // 15 minutos para descargar
                    camion.setStatus(CamionEstado.TruckStatus.PROCESSING);
                    
                    // Actualizar capacidad disponible
                    double dispAntes = camion.getCapacidadDisponible();
                    if (dispAntes >= ev.getPedido().getVolumen()) {
                        camion.setCapacidadDisponible(dispAntes - ev.getPedido().getVolumen());
                    }
                    
                    // Marcar el pedido como atendido
                    ev.getPedido().setAtendido(true);
                    
                    System.out.printf("✅ Entrega realizada - Pedido %d por camión %s en %s%n", 
                                     ev.getPedido().getId(), camion.getPlantilla().getId(), tiempoActual);

                    itEv.remove();
                }
            }
        }
    }

    /**
     * Procesa el retorno de camiones que han completado sus entregas.
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     * @return true si se requiere replanificación
     */
    private void procesarRetorno(ExecutionContext contexto, LocalDateTime tiempoActual, String simulationId) {
        Iterator<CamionEstado> it = contexto.getCamiones().iterator();
        while (it.hasNext()) {
            CamionEstado camion = it.next();
            if(camion.getStatus() == CamionEstado.TruckStatus.PROCESSING && 
               (camion.getTiempoLibre() == null || !camion.getTiempoLibre().isAfter(tiempoActual))) {
                double falta = camion.getPlantilla().getCapacidadCarga() - camion.getCapacidadDisponible();
                int sx = camion.getX(), sy = camion.getY();
                int dxPlant = contexto.getDepositoX(), dyPlant = contexto.getDepositoY();
                int distMin = Math.abs(sx - dxPlant) + Math.abs(sy - dyPlant);
                TanqueDinamico mejor = null;
                for(TanqueDinamico tq : contexto.getTanques()) {
                    if(tq.getDisponible() >= falta) {
                        int dist = Math.abs(sx - tq.getPosX()) + Math.abs(sy - tq.getPosY());
                        if(dist < distMin) {
                            distMin = dist;
                            mejor = tq;
                        }
                    }
                }
                int destX = (mejor != null) ? mejor.getPosX() : dxPlant;
                int destY = (mejor != null) ? mejor.getPosY() : dyPlant;
                camion.setReabastecerEnTanque(mejor);
                if(mejor != null) {
                    mejor.setDisponible(mejor.getDisponible() - falta);
                }
                camion.setEnRetorno(true);
                camion.setStatus(TruckStatus.RETURNING);
                camion.setRetHora(tiempoActual);
                camion.setRetStartX(sx);
                camion.setRetStartY(sy);
                camion.setRetDestX(destX);
                camion.setRetDestY(destY);

                List<Point> returnPath = buildManhattanPath(sx, sy, destX, destY, tiempoActual, contexto);
                camion.setRuta(returnPath);
                camion.setPasoActual(0);
                camion.getHistory().addAll(returnPath);
            }
        }
    }

    
    /**
     * Procesa las averías según el turno actual.
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     * @return true si ocurrieron cambios que requieren replanificación
     */
    private boolean procesarAverias(ExecutionContext contexto, LocalDateTime tiempoActual) {
        boolean replanificar = false;
        
        // Determinar el turno actual
        String turnoActual = turnoDeDateTime(tiempoActual);
        
        // Si cambió el turno, limpiar estados de averías anteriores
        if (!turnoActual.equals(contexto.getTurnoAnterior())) {
            contexto.setTurnoAnterior(turnoActual);
            contexto.getAveriasAplicadas().clear();
            
        }
        
        // Aplicar averías programadas para este turno
        Map<String, Averia> averiasTurno = contexto.getAveriasPorTurno().getOrDefault(turnoActual, Collections.emptyMap());
        for (Map.Entry<String, Averia> entry : averiasTurno.entrySet()) {
            String key = turnoActual + "_" + entry.getKey();
            
            CamionEstado c = findCamion(entry.getKey(), contexto);
            Averia datoaveria = entry.getValue();
            // Para averías cargadas desde archivo
            if (datoaveria.isFromFile()) {
                if (contexto.getAveriasAplicadas().contains(key)) continue;
                // Solo aplicar si el camión está entregando y tiene una ruta asignada
                if (c != null && c.getStatus() == CamionEstado.TruckStatus.DELIVERING && 
                    c.getRutaActual() != null && !c.getRutaActual().isEmpty()) {
                    
                    Integer puntoAveria = contexto.getPuntosAveria().get(entry.getKey());
                    
                    // Solo aplicar si hay un punto de avería calculado y el camión está en ese punto
                    if (puntoAveria != null && c.getPasoActual() == puntoAveria) {
                        System.out.println("🔍 Camión " + c.getPlantilla().getId() + 
                                         " llegó al punto de avería calculado (paso " + puntoAveria + 
                                         " de " + c.getRutaActual().size() + ")");
                        
                        if (aplicarAveria(c, datoaveria, tiempoActual, turnoActual, contexto, key)) {
                            replanificar = true;
                            // Marcar la avería como aplicada
                            contexto.getAveriasAplicadas().add(key);
                        }

                    }
                }
            } else {
                // Para averías manuales, aplicar inmediatamente si el camión está disponible
                if (c != null && (c.getTiempoLibre() == null || !c.getTiempoLibre().isAfter(tiempoActual))) {
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
            if (c != null && (c.getTiempoLibre() == null || !c.getTiempoLibre().isAfter(tiempoActual))) {
                it.remove();
                // Restaurar estado del camión tras reparación
                // Reiniciar estado de taller para futuras averías
                c.setEnTaller(false);
                c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                c.setTiempoLibre(null);
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
    /**
    * Genera puntos de avería para todos los camiones con rutas asignadas
    */
    private void generarPuntosAverias(ExecutionContext contexto) {
    
    // Recorrer todos los camiones
        for (CamionEstado camion : contexto.getCamiones()) {
            // Solo calcular puntos de avería para camiones con ruta asignada
            if (camion.tieneRutaAsignada() && camion.getRutaActual().size() > 0) {
                calcularPuntosAveria(camion, contexto);
            }
        }
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
        camion.setTiempoLibre(tiempoActual.plusMinutes(penal));
        camion.setTiempoInicioAveria(tiempoActual); // Guardar cuándo inicia la avería
        camion.setStatus(CamionEstado.TruckStatus.BREAKDOWN);
        // Guardar el tipo de avería en el camión para usarlo después
        camion.setTipoAveriaActual(averia.getTipoIncidente());
        // Reiniciar estado de taller para permitir teleportación si es necesario
        camion.setEnTaller(false);

        // --- Acciones inmediatas por avería ---
        // Remover eventos de entrega pendientes para este camión
        removerEventosEntregaDeCamion(camion.getPlantilla().getId(), contexto);
        // Liberar pedidos pendientes y limpiar ruta
        for (Pedido pPend : new ArrayList<>(camion.getPedidosCargados())) {
            pPend.setProgramado(false); // volver a la cola de planificación
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

        
        System.out.println("🔧 Avería tipo " + averia.getTipoIncidente() + 
                        " aplicada al camión " + camion.getPlantilla().getId() + 
                        " en " + tiempoActual + 
                        ". Tiempo estimado de reparación: " + penal + " minutos.");
        
        return true; // Siempre replanificar después de una avería
    }
    
    // Este método se ha movido a SimulacionService

    
    // --- Métodos auxiliares privados ---

    private void aplicarRutas(LocalDateTime tiempoActual, List<Ruta> rutas, List<Pedido> activos, ExecutionContext contexto, String simulationId) {
        rutas.removeIf(r -> r.getPedidoIds() == null || r.getPedidoIds().isEmpty());

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
                System.out.printf("⚠ %s: Ruta descartada para %s (no cabe volumen) → %s%n",
                        tiempoActual, real.getPlantilla().getId(),
                        r.getPedidoIds().stream().map(i -> activos.get(i).getId()).collect(Collectors.toList()));
                itR.remove();
            }
        }

        // B) Aplicar cada ruta al estado real
        for (Ruta ruta : rutas) {

            CamionEstado camion = findCamion(ruta.getCamionId(), contexto);
            Pedido nuevo = activos.get(ruta.getPedidoIds().get(0));

            // ─── INSTRUMENTACIÓN DE LOGS ────────────────────────────
            boolean condStatus    = camion.getStatus() == CamionEstado.TruckStatus.DELIVERING;
            boolean condValido    = esDesvioValido(camion, nuevo, tiempoActual, contexto);
            boolean condCapacidad = camion.getCapacidadDisponible() >= nuevo.getVolumen();
            System.out.printf(
                    "🔍 Desvío? Camión=%s Pedido=%d | status=DELIVERING?%b | esDesvíoValido?%b | capSuficiente?%b%n",
                    camion.getPlantilla().getId(),
                    nuevo.getId(),
                    condStatus,
                    condValido,
                    condCapacidad
            );

            List<Point> path;

            if (camion.getStatus() == CamionEstado.TruckStatus.DELIVERING
                    && esDesvioValido(camion, nuevo, tiempoActual, contexto)
                    && camion.getCapacidadDisponible() >= nuevo.getVolumen()) {

                int idx = posicionOptimaDeInsercion(camion, nuevo, tiempoActual, contexto);
                camion.getPedidosCargados().add(idx, nuevo);
                camion.setCapacidadDisponible(camion.getCapacidadDisponible() - nuevo.getVolumen());
                System.out.printf("🔀 %s: Desvío – insertado Pedido #%d en %s en posición %d%n",
                        tiempoActual, nuevo.getId(), camion.getPlantilla().getId(), idx);

                int cx = camion.getX(), cy = camion.getY();
                path = buildManhattanPath(cx, cy, nuevo.getX(), nuevo.getY(), tiempoActual, contexto);
                int pasos = path.size();
                //int dist = Math.abs(cx - nuevo.getX()) + Math.abs(cy - nuevo.getY());
                int minutosViaje = (int) Math.ceil(pasos * (60.0 / 50.0));

                camion.setRuta(path);
                camion.getHistory().addAll(path);
                LocalDateTime tiempoEntrega = tiempoActual.plusMinutes(minutosViaje);
                contexto.getEventosEntrega().add(new EntregaEvent(tiempoEntrega, camion.getPlantilla().getId(), nuevo));
                nuevo.setProgramado(true);
                System.out.printf("🕒 eventoEntrega programado (desvío) %s → (%d,%d)%n",
                        tiempoEntrega, nuevo.getX(), nuevo.getY());

            } else {
                // Asignación normal
                camion.getPedidosCargados().clear();
                camion.getPedidosCargados().add(nuevo);
                camion.setStatus(CamionEstado.TruckStatus.DELIVERING);

                int cx = camion.getX(), cy = camion.getY();
                for (int pedidoIdx : ruta.getPedidoIds()) {
                    Pedido p = activos.get(pedidoIdx);
                    if (camion.getCapacidadDisponible() < p.getVolumen()) {
                        System.out.printf("⚠ %s: Camión %s sin espacio para Pedido #%d%n",
                                tiempoActual, camion.getPlantilla().getId(), p.getId());
                        continue;
                    }
                    System.out.printf("⏱️ %s: Asignando Pedido #%d al Camión %s%n (%d,%d)",
                            tiempoActual, p.getId(), camion.getPlantilla().getId(), p.getX(), p.getY());

                    path = buildManhattanPath(cx, cy, p.getX(), p.getY(), tiempoActual, contexto);
                    int dist = path.size();
                    int minutosViaje = (int) Math.ceil(dist * (60.0 / 50.0));

                    camion.setRuta(path);
                    camion.setPasoActual(0);
                    camion.getHistory().addAll(path);
                    p.setProgramado(true);

                    LocalDateTime tiempoEntrega = tiempoActual.plusMinutes(minutosViaje);
                    contexto.getEventosEntrega().add(new EntregaEvent(
                            tiempoEntrega, camion.getPlantilla().getId(), p
                    ));
                    System.out.printf("🕒 eventoEntrega programado %s → (%d,%d)%n",
                            tiempoEntrega, p.getX(), p.getY());
                    Point last = path.get(path.size() - 1);
                    // camion.setX(p.getX());
                    // camion.setY(p.getY());
                    cx = last.x;; cy = last.y;
                }
            }
        }
    }

    private boolean esDesvioValido(CamionEstado c, Pedido p, LocalDateTime tiempoActual, ExecutionContext contexto) {
        double disponible = c.getCapacidadDisponible();
        LocalDateTime hora = tiempoActual;
        int prevX = c.getX(), prevY = c.getY();

        // — Primer tramo: al NUEVO pedido —
        List<Point> pathToNew = buildManhattanPath(prevX, prevY, p.getX(), p.getY(), hora, contexto);
        if (pathToNew == null) return false;           // imposible alcanzar
        hora = hora.plusMinutes(pathToNew.size());     // 1 paso = 1 minuto
        hora = hora.plusMinutes(15);                   // +15 min de descarga
        if (hora.isAfter(p.getTiempoLimite())) return false;
        disponible -= p.getVolumen();
        if (disponible < 0) return false;

        // avanzamos "virtualmente" a la posición del pedido
        prevX = p.getX();
        prevY = p.getY();

        // — Siguientes tramos: los pedidos ya en rutaPendiente —
        for (Pedido orig : c.getPedidosCargados()) {
            List<Point> pathSeg = buildManhattanPath(prevX, prevY, orig.getX(), orig.getY(), hora, contexto);
            if (pathSeg == null) return false;         // no hay ruta libre
            hora = hora.plusMinutes(pathSeg.size());
            hora = hora.plusMinutes(15);               // tiempo de servicio
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
        LocalDateTime mejorHoraEntrega = tiempoActual.plusYears(100); // Una fecha muy lejana

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
                hora = hora.plusMinutes(15);
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
                // 6) Avanzar "virtual" a la posición del pedido
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

    private CamionEstado findCamion(String camionId, ExecutionContext estado) {
        return estado.getCamiones().stream()
                .filter(c -> c.getPlantilla().getId().equals(camionId))
                .findFirst().orElse(null);
    }


    
    private String turnoDeDateTime(LocalDateTime dateTime) {
        int hour = dateTime.getHour();
        int minute = dateTime.getMinute();
        int minutesOfDay = hour * 60 + minute;
        
        if (minutesOfDay < 480) return "T1"; // Antes de las 8:00
        else if (minutesOfDay < 960) return "T2"; // Entre 8:00 y 16:00
        else return "T3"; // Después de las 16:00
    }

    private boolean isBlockedMove(int x, int y, LocalDateTime t, ExecutionContext estado) {
        for (Bloqueo b : estado.getBloqueos()) {
            // The logic for checking if a point is inside a polygonal block is complex.
            // Assuming a simplified check here. The actual logic resides in Bloqueo.estaBloqueado
            if (b.isActiveAt(t) && b.estaBloqueado(t, new java.awt.Point(x,y))) {
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
                if (closedSet.contains(neighborPos) || 
                    isBlockedMove(neighborPos.x, neighborPos.y, startTime.plusMinutes(currentNode.gCost + 1), estado)) {
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
        Point current = new Point(x1, y1);
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
    private void actualizarBloqueosActivos(ExecutionContext contexto, LocalDateTime tiempoActual, String simulationId) {
        List<Bloqueo> todosBloqueos = contexto.getBloqueos();
        
        // 1. Verificar bloqueos que deberían activarse
        for (Bloqueo b : todosBloqueos) {
            // Si el bloqueo está activo en este tiempo pero no estaba activo antes
            if (b.isActiveAt(tiempoActual) && b.getLastKnownState() != Bloqueo.Estado.ACTIVO) {
                // Añadir a la lista de activos
                contexto.addBloqueoActivo(b);
                // Actualizar estado
                b.setLastKnownState(Bloqueo.Estado.ACTIVO);
            
                
                System.out.printf("🚧 Bloqueo activado en %s: %s (desde %s hasta %s)%n", 
                        tiempoActual, b.getDescription(), b.getStartTime(), b.getEndTime());
            }
        }
        
        // 2. Verificar bloqueos que deberían desactivarse
        List<Bloqueo> bloqueosActivos = new ArrayList<>(contexto.getBloqueosActivos());
        for (Bloqueo b : bloqueosActivos) {
            if (!b.isActiveAt(tiempoActual)) {
                // Si estaba marcado como activo, notificamos que ha terminado
                if (b.getLastKnownState() == Bloqueo.Estado.ACTIVO) {
                    // Eliminar de la lista de activos
                    contexto.removeBloqueoActivo(b);
                    // Actualizar estado
                    b.setLastKnownState(Bloqueo.Estado.TERMINADO);
                    
                    System.out.printf("✅ Bloqueo finalizado en %s: %s (desde %s hasta %s)%n", 
                            tiempoActual, b.getDescription(), b.getStartTime(), b.getEndTime());
                }
            }
        }
    }
}
