package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


import pe.pucp.plg.dto.AveriaDTO;
import pe.pucp.plg.dto.SimulationRequest;
import pe.pucp.plg.dto.SimulationStatusDTO;
import pe.pucp.plg.model.common.Averia;

import pe.pucp.plg.dto.*;
import pe.pucp.plg.dto.enums.EventType;

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


import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.awt.Point;

@Service
public class SimulacionService {

    private final SimulationManagerService simulationManagerService;
    private final ACOPlanner acoPlanner;

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
    public SimulacionService(SimulationManagerService simulationManagerService) {
        this.simulationManagerService = simulationManagerService;
        this.acoPlanner = new ACOPlanner();
    }

    /**
     * Initiates a new simulation instance using the SimulationManagerService.
     * The manager creates a fresh, initialized ExecutionContext.
     * This version is for resetting the operational context or creating a simple simulation.
     * @return The ID of the newly created simulation context.
     */
    public String iniciarSimulacion() {
        // This method now primarily serves to initialize/reset the operational context
        // or create a basic simulation context if needed elsewhere without file inputs.
        // For simulations based on file inputs, use iniciarSimulacion(SimulationRequest request).
        simulationManagerService.initializeOperationalContext(); // Or a more specific reset method if available
        // If it's about creating a generic new simulation context ID:
        // return simulationManagerService.crearContextoSimulacion(); 
        return "operational_context_reset_or_initialized"; // Placeholder, adjust as per exact need
    }

    /**
     * Advances the simulation identified by simulationId by one minute.
     * @param simulationId The ID of the simulation to step forward.
     * @return The new current time of the simulation.
     */
    public int stepOneMinute(String simulationId) {
        // Obtener el contexto de ejecución
        ExecutionContext contexto = simulationManagerService.getContextoSimulacion(simulationId);
        if (contexto == null) {
            if ("operational".equals(simulationId)) {
                contexto = simulationManagerService.getOperationalContext();
            } else {
                throw new IllegalArgumentException("Simulation context not found for ID: " + simulationId);
            }
        }

        // AVANZAR EL TIEMPO
        int tiempoActual = contexto.getCurrentTime() + 1;
        contexto.setCurrentTime(tiempoActual);
        boolean replanificar = (tiempoActual == 0); // Replanificar al inicio siempre
        
        // 1. Recargar tanques (cada 24 horas) y cargar nuevos pedidos y bloqueos para el siguiente día
        if (tiempoActual > 0 && tiempoActual % 1440 == 0) {
            System.out.println("⛽ Recarga diaria de tanques en t+" + tiempoActual);
            for (TanqueDinamico tq : contexto.getTanques()) {
                tq.setDisponible(tq.getCapacidadTotal());
                TanqueDTO tanqueDTO = MapperUtil.toTanqueDTO(tq);
                EventDTO eventoTanque = EventDTO.of(EventType.TANK_LEVEL_UPDATED, tanqueDTO);
                eventPublisher.publicarEventoSimulacion(simulationId, eventoTanque);
            }
            
            // Determinar qué día estamos y cargar datos para ese día
            int diaActual = tiempoActual / 1440 + 1; // +1 porque el día 1 empieza en tiempo 0
            
            // Solo cargamos datos nuevos si estamos dentro del período de simulación
            if (diaActual <= contexto.getDuracionDias()) {
                // Calcular la fecha para este día
                LocalDate fechaActual = contexto.getFechaInicio().plusDays(diaActual - 1);
                System.out.println("📅 Cargando datos para el día: " + fechaActual);
                
                // Cargar pedidos y bloqueos para este día
                List<Pedido> nuevoPedidos = ResourceLoader.cargarPedidosParaFecha(fechaActual);
                List<Bloqueo> nuevoBloqueos = ResourceLoader.cargarBloqueosParaFecha(fechaActual);
                
                // Ajustar los tiempos de los pedidos para que empiecen en el minuto correcto del día actual
                for (Pedido p : nuevoPedidos) {
                    // El tiempo de creación ya está en minutos relativos al día
                    // Lo ajustamos sumando los minutos totales hasta el inicio del día actual
                    p.setTiempoCreacion(p.getTiempoCreacion() - ((diaActual - 1) * 1440) % (24*60) + tiempoActual);
                    p.setTiempoLimite(p.getTiempoLimite() - ((diaActual - 1) * 1440) % (24*60) + tiempoActual);
                }
                
                // Ajustar los tiempos de los bloqueos para que empiecen en el minuto correcto
                for (Bloqueo b : nuevoBloqueos) {
                    b.setStartMin(b.getStartMin() - ((diaActual - 1) * 1440) % (24*60) + tiempoActual);
                    b.setEndMin(b.getEndMin() - ((diaActual - 1) * 1440) % (24*60) + tiempoActual);
                }
                
                // Añadir los nuevos pedidos al mapa de pedidos por tiempo
                for (Pedido p : nuevoPedidos) {
                    contexto.getPedidosPorTiempo().computeIfAbsent(p.getTiempoCreacion(), k -> new ArrayList<>()).add(p);
                }
                
                // Añadir los nuevos bloqueos
                contexto.getBloqueos().addAll(nuevoBloqueos);
                
                System.out.printf("🔄 Día %d: Cargados %d nuevos pedidos y %d bloqueos%n", 
                        diaActual, nuevoPedidos.size(), nuevoBloqueos.size());
                
                // Si hay nuevos datos, replanificar
                if (!nuevoPedidos.isEmpty() || !nuevoBloqueos.isEmpty()) {
                    replanificar = true;
                }
            }
        }
        
        // 2 Procesar eventrocesarEventosEntrega(contexto, tiempoActual, simulationos de entrega programados
        procesarEventosEntrega(contexto, tiempoActual, simulationId);

        // 3. Iniciar retorno de camiones
        procesarRetorno(contexto, tiempoActual,simulationId);

        // 4. Avanzar cada camión en sus rutas actuales
        for (CamionEstado c : contexto.getCamiones()) {
            if (c.tienePasosPendientes()) {
                c.avanzarUnPaso(); // El camión se mueve según su ruta actual
            } else if(c.getStatus() == CamionEstado.TruckStatus.RETURNING) {
                // Camión ha llegado al final de su ruta de retorno
                c.setCapacidadDisponible(c.getPlantilla().getCapacidadCarga());
                c.setCombustibleDisponible(c.getPlantilla().getCapacidadCombustible());
                c.setEnRetorno(false);
                c.setReabastecerEnTanque(null);
                c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                c.setTiempoLibre(tiempoActual + 15); // Tiempo de descarga/recarga
                
                System.out.printf("🚚 Camión %s ha completado su retorno y está disponible en t+%d%n", 
                                 c.getPlantilla().getId(), tiempoActual + 15);
                // Emitir evento TRUCK_STATE_UPDATED a través de EventPublisherService
                CamionDTO camionDTO = MapperUtil.toCamionDTO(c);
                EventDTO eventoCamion = EventDTO.of(EventType.TRUCK_STATE_UPDATED,camionDTO);
                eventPublisher.publicarEventoSimulacion(simulationId, eventoCamion);
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
            System.out.printf("🆕 t+%d: Pedido #%d recibido (destino=(%d,%d), vol=%.1fm³, límite t+%d)%n",
                    tiempoActual, p.getId(), p.getX(), p.getY(), p.getVolumen(), p.getTiempoLimite());
        }
        if (!pedidosAInyectar.isEmpty()) replanificar = true;

        // 6. Comprobar colapso
        Iterator<Pedido> itP = contexto.getPedidos().iterator();
        while (itP.hasNext()) {
            Pedido p = itP.next();
            if (!p.isAtendido() && !p.isDescartado() && tiempoActual > p.getTiempoLimite()) {
                // Emitir evento SIMULATION_COLLAPSED con pedido afectado
                PedidoDTO pedidoDTO = MapperUtil.toPedidoDTO(p);
                EventDTO eventoColapso = EventDTO.of(EventType.SIMULATION_COLLAPSED, pedidoDTO);
                eventPublisher.publicarEventoSimulacion(simulationId, eventoColapso);

                System.out.printf("💥 Colapso en t+%d, pedido %d incumplido%n",
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
        Map<Pedido, Integer> entregaActual = new HashMap<>();
        for (EntregaEvent ev : contexto.getEventosEntrega()) {
            entregaActual.put(ev.getPedido(), ev.time);
        }
        List<Pedido> pendientes = contexto.getPedidos().stream()
                .filter(p -> !p.isAtendido() && !p.isDescartado() && !p.isProgramado() && p.getTiempoCreacion() <= tiempoActual)
                .collect(Collectors.toList());

        List<Pedido> candidatos = new ArrayList<>();
        for (Pedido p : pendientes) {
            if (tiempoActual + 60 >= p.getTiempoLimite()) {
                candidatos.add(p);
                continue;
            }
            Integer tPrev = entregaActual.get(p);
            if (tPrev == null) {
                candidatos.add(p);
            } else {
                int mejorAlt = tPrev;
                for (CamionEstado est : flotaEstado) {
                    if (est.getCapacidadDisponible() < p.getVolumen()) continue;
                    int dt = Math.abs(est.getX() - p.getX()) + Math.abs(est.getY() - p.getY());
                    int llegada = tiempoActual + dt;
                    if (llegada < mejorAlt) mejorAlt = llegada;
                }
                if (mejorAlt < tPrev) candidatos.add(p);
            }
        }
        candidatos.removeIf(p -> {
            Integer entregaMin = entregaActual.get(p);
            return entregaMin != null && entregaMin - tiempoActual <= 1;
        });
        
        // 9. Replanificar rutas si es necesario
        
        // Si hay nuevos pedidos, averías resueltas, o es inicio de simulación
        if (replanificar) {
            System.out.println("🔄 Ejecutando replanificación en t+" + tiempoActual);
            
            // 4.1 Solicitar al ACOPlanner que calcule nuevas rutas óptimas
            List<Ruta> nuevasRutas = acoPlanner.planificarRutas(candidatos, flotaEstado, tiempoActual, contexto);
            
            // 4.2 Traducir el plan a acciones concretas sobre el estado real
            aplicarRutas(tiempoActual, nuevasRutas, candidatos, contexto,simulationId);
            
            // 4.3 Guardar las rutas en el contexto para visualización o análisis
            contexto.setRutas(nuevasRutas);
        }
        
        return contexto.getCurrentTime();
    }
    
    /**
     * Procesa los eventos de entrega que ocurren en el tiempo actual.
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     */
    private void procesarEventosEntrega(ExecutionContext contexto, int tiempoActual, String simulationId) {
        Iterator<EntregaEvent> itEv = contexto.getEventosEntrega().iterator();
        while (itEv.hasNext()) {
            EntregaEvent ev = itEv.next();
            if (ev.time == tiempoActual) {
                CamionEstado camion = findCamion(ev.getCamionId(), contexto);
                if (camion != null) {
                    // Actualizar posición y estado del camión
                    camion.setX(ev.getPedido().getX());
                    camion.setY(ev.getPedido().getY());
                    camion.setTiempoLibre(tiempoActual + 15); // 15 minutos para descargar
                    camion.setStatus(CamionEstado.TruckStatus.PROCESSING);
                    
                    // Actualizar capacidad disponible
                    double dispAntes = camion.getCapacidadDisponible();
                    if (dispAntes >= ev.getPedido().getVolumen()) {
                        camion.setCapacidadDisponible(dispAntes - ev.getPedido().getVolumen());
                    }
                    
                    // Marcar el pedido como atendido
                    ev.getPedido().setAtendido(true);
                    
                    System.out.printf("✅ Entrega realizada - Pedido %d por camión %s en t+%d%n", 
                                     ev.getPedido().getId(), camion.getPlantilla().getId(), tiempoActual);

                    // Emitir evento ORDER_STATE_UPDATED a través de EventPublisherService
                    PedidoDTO pedidoDTO = MapperUtil.toPedidoDTO(ev.getPedido());
                    EventDTO evento2 = EventDTO.of(EventType.ORDER_STATE_UPDATED, pedidoDTO);
                    eventPublisher.publicarEventoSimulacion(simulationId, evento2); // Método para topic dinámico /topic/simulation/{id}
                    // Eliminar el evento procesado
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
    private void procesarRetorno(ExecutionContext contexto, int tiempoActual, String simulationId) {
        Iterator<CamionEstado> it = contexto.getCamiones().iterator();
        while (it.hasNext()) {
            CamionEstado camion = it.next();
            if(camion.getStatus() == CamionEstado.TruckStatus.PROCESSING && camion.getTiempoLibre() <= tiempoActual) {
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
                // Emitir evento TRUCK_STATE_UPDATED a través de EventPublisherService
                CamionDTO camionDTO = MapperUtil.toCamionDTO(camion);
                EventDTO eventoCamion = EventDTO.of(EventType.TRUCK_STATE_UPDATED,camionDTO);
                eventPublisher.publicarEventoSimulacion(simulationId, eventoCamion);
            }
        }
    }

    
    /**
     * Procesa las averías según el turno actual.
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     * @return true si ocurrieron cambios que requieren replanificación
     */
    private boolean procesarAverias(ExecutionContext contexto, int tiempoActual) {
        boolean replanificar = false;
        
        // Determinar el turno actual
        String turnoActual = turnoDeMinuto(tiempoActual);
        
        // Si cambió el turno, limpiar estados de averías anteriores

        if (!turnoActual.equals(contexto.getTurnoAnterior())) {
            contexto.setTurnoAnterior(turnoActual);
            contexto.getAveriasAplicadas().clear();
            contexto.getCamionesInhabilitados().clear();          
        }
        
        // Aplicar averías programadas para este turno
        Map<String, String> averiasTurno = contexto.getAveriasPorTurno().getOrDefault(turnoActual, Collections.emptyMap());
   
        for (Map.Entry<String, String> entry : averiasTurno.entrySet()) {
            String key = turnoActual + "_" + entry.getKey();
            if (contexto.getAveriasAplicadas().contains(key)) continue;
            
            CamionEstado c = findCamion(entry.getKey(), contexto);
            //System.out.printf("📌 TgetTiempoLibre: %d%n", c.getTiempoLibre());
            //System.out.printf("📌 tiempoActual : %d%n",tiempoActual);
            if (c != null && c.getTiempoLibre() <= tiempoActual) {
                // Determinar penalización según tipo de avería
                String tipoaveria = entry.getValue();
                int penal=calcularTiempoAveria(turnoActual,tipoaveria,tiempoActual);           
                c.setTiempoLibre(tiempoActual + penal);
                c.setStatus(CamionEstado.TruckStatus.BREAKDOWN);
                contexto.getAveriasAplicadas().add(key);
                contexto.getCamionesInhabilitados().add(c.getPlantilla().getId());
                
                System.out.printf("🔧 Avería tipo %s en camión %s - Inhabilitado hasta t+%d%n", 
                                 entry.getValue(), c.getPlantilla().getId(), tiempoActual + penal);
                                 
                replanificar = true;
            }
        }
        
        // Revisar camiones que ya pueden volver a servicio
        Iterator<String> it = contexto.getCamionesInhabilitados().iterator();
        while (it.hasNext()) {
            CamionEstado c = findCamion(it.next(), contexto);
            if (c != null && c.getTiempoLibre() <= tiempoActual) {
                it.remove();
                c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                System.out.printf("🚚 Camión %s reparado y disponible nuevamente en t+%d%n", 
                                 c.getPlantilla().getId(), tiempoActual);
                replanificar = true;
            }
        }
        
        return replanificar;
    }

    /**
     * Gets the current time of a specific simulation.
     * @param simulationId The ID of the simulation.
     * @return The current time.
     */
    public int getTiempoActual(String simulationId) {
        ExecutionContext currentContext = simulationManagerService.getContextoSimulacion(simulationId);
        if (currentContext == null) {
            throw new IllegalArgumentException("Simulation context not found for ID: " + simulationId);
        }
        return currentContext.getCurrentTime();
    }

    /**
     * Initiates a new simulation based on date range specified in the request.
     * @param request The request containing fecha inicio, duracion, and simulation name.
     * @return A DTO with the status and ID of the new simulation.
     */
    public SimulationStatusDTO iniciarSimulacion(SimulationRequest request) {
        try {
            // 1. Crear el contexto
            String simulationId = simulationManagerService.crearContextoSimulacion();
            EventDTO eventoInicio = EventDTO.of(EventType.SIMULATION_STARTED, null); // No payload necesario o poner info básica
            eventPublisher.publicarEventoSimulacion(simulationId, eventoInicio);

            ExecutionContext currentSimContext = simulationManagerService.getContextoSimulacion(simulationId);
    
            if (currentSimContext == null) {
                throw new RuntimeException("No se pudo crear el contexto de simulación.");
            }
            
            // 2. Validar la fecha de inicio
            if (request.getFechaInicio() == null || request.getFechaInicio().isBlank()) {
                throw new IllegalArgumentException("La fecha de inicio es obligatoria.");
            }
            
            // 3. Convertir la fecha de inicio a LocalDate
            LocalDate fechaInicio = LocalDate.parse(request.getFechaInicio(), DateTimeFormatter.ISO_LOCAL_DATE);
            currentSimContext.setFechaInicio(fechaInicio); // Asumimos que este campo existe o lo crearemos después
            currentSimContext.setDuracionDias(request.getDuracionDias()); // Asumimos que este campo existe
            
            // 4. Cargar pedidos y bloqueos para el primer día
            List<Pedido> pedidosDiaUno = ResourceLoader.cargarPedidosParaFecha(fechaInicio);
            List<Bloqueo> bloqueosDiaUno = ResourceLoader.cargarBloqueosParaFecha(fechaInicio);
            
            // 5. Organizar los pedidos por tiempo
            Map<Integer, List<Pedido>> pedidosPorTiempo = new HashMap<>();
            for (Pedido p : pedidosDiaUno) {
                pedidosPorTiempo.computeIfAbsent(p.getTiempoCreacion(), k -> new ArrayList<>()).add(p);
            }
            currentSimContext.setPedidosPorTiempo(pedidosPorTiempo);
            
            // 6. Añadir los pedidos iniciales (tiempo 0) a la lista activa
            List<Pedido> initialPedidos = pedidosPorTiempo.getOrDefault(0, new ArrayList<>());
            currentSimContext.setPedidos(new ArrayList<>(initialPedidos));
            if (currentSimContext.getPedidosPorTiempo().containsKey(0)) {
                currentSimContext.getPedidosPorTiempo().remove(0);
            }
            
            // 7. Establecer los bloqueos iniciales
            currentSimContext.setBloqueos(bloqueosDiaUno);
            
            // 8. Inicializar las estructuras de datos necesarias
            currentSimContext.getEventosEntrega().clear();
            currentSimContext.getAveriasAplicadas().clear();
            currentSimContext.getCamionesInhabilitados().clear();
            currentSimContext.setRutas(new ArrayList<>());
    
            String nombre = request.getNombreSimulacion() != null ? request.getNombreSimulacion() : "Simulación " + simulationId.substring(0, 8);
            SimulationStatusDTO status = new SimulationStatusDTO();
            status.setSimulationId(simulationId);
            status.setNombreSimulacion(nombre);
            status.setEstado("INITIALIZED"); 
            status.setAvance(0);
    
            return status;
    
        } catch (Exception e) {
            // Tu `System.err.println` me ayudó a encontrar esto. ¡Excelente!
            System.err.println("Error starting simulation: " + e.getMessage());
            throw new RuntimeException("Error al iniciar simulación con request: " + e.getMessage(), e);
        }
    }

    // --- Métodos auxiliares privados ---

    private void aplicarRutas(int tiempoActual, List<Ruta> rutas, List<Pedido> activos, ExecutionContext contexto, String simulationId) {
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
                System.out.printf("⚠ t+%d: Ruta descartada para %s (no cabe volumen) → %s%n",
                        tiempoActual, real.getPlantilla().getId(),
                        r.getPedidoIds().stream().map(i -> activos.get(i).getId()).collect(Collectors.toList()));
                itR.remove();
            }
        }

        // B) Aplicar cada ruta al estado real
        for (Ruta ruta : rutas) {
            // Emitir evento ROUTE_ASSIGNED para cada ruta asignada
            RutaDTO rutaDTO = MapperUtil.toRutaDTO(ruta);
            EventDTO eventoRuta = EventDTO.of(EventType.ROUTE_ASSIGNED,rutaDTO);
            eventPublisher.publicarEventoSimulacion(simulationId, eventoRuta);

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
                System.out.printf("🔀 t+%d: Desvío – insertado Pedido #%d en %s en posición %d%n",
                        tiempoActual, nuevo.getId(), camion.getPlantilla().getId(), idx);

                int cx = camion.getX(), cy = camion.getY();
                path = buildManhattanPath(cx, cy, nuevo.getX(), nuevo.getY(), tiempoActual, contexto);
                int pasos = path.size();
                //int dist = Math.abs(cx - nuevo.getX()) + Math.abs(cy - nuevo.getY());
                int tViaje = (int) Math.ceil(pasos * (60.0 / 50.0));

                camion.setRuta(path);
                camion.getHistory().addAll(path);
                contexto.getEventosEntrega().add(new EntregaEvent(tiempoActual + tViaje, camion.getPlantilla().getId(), nuevo));
                nuevo.setProgramado(true);
                System.out.printf("🕒 eventoEntrega programado (desvío) t+%d → (%d,%d)%n",
                        tiempoActual + tViaje, nuevo.getX(), nuevo.getY());

            } else {
                // Asignación normal
                camion.getPedidosCargados().clear();
                camion.getPedidosCargados().add(nuevo);
                camion.setStatus(CamionEstado.TruckStatus.DELIVERING);

                int cx = camion.getX(), cy = camion.getY();
                for (int pedidoIdx : ruta.getPedidoIds()) {
                    Pedido p = activos.get(pedidoIdx);
                    if (camion.getCapacidadDisponible() < p.getVolumen()) {
                        System.out.printf("⚠ t+%d: Camión %s sin espacio para Pedido #%d%n",
                                tiempoActual, camion.getPlantilla().getId(), p.getId());
                        continue;
                    }
                    System.out.printf("⏱️ t+%d: Asignando Pedido #%d al Camión %s%n (%d,%d)",
                            tiempoActual, p.getId(), camion.getPlantilla().getId(), p.getX(), p.getY());

                    path = buildManhattanPath(cx, cy, p.getX(), p.getY(), tiempoActual, contexto);
                    int dist = path.size();
                    int tViaje = (int) Math.ceil(dist * (60.0 / 50.0));

                    camion.setRuta(path);
                    camion.setPasoActual(0);
                    camion.getHistory().addAll(path);
                    p.setProgramado(true);

                    contexto.getEventosEntrega().add(new EntregaEvent(
                            tiempoActual + tViaje, camion.getPlantilla().getId(), p
                    ));
                    System.out.printf("🕒 eventoEntrega programado t+%d → (%d,%d)%n",
                            tiempoActual + tViaje, p.getX(), p.getY());
                    Point last = path.get(path.size() - 1);
                    // camion.setX(p.getX());
                    // camion.setY(p.getY());
                    cx = last.x;; cy = last.y;
                }
            }
        }
    }

    private boolean esDesvioValido(CamionEstado c, Pedido p, int tiempoActual, ExecutionContext contexto) {
        double disponible = c.getCapacidadDisponible();
        int hora = tiempoActual;
        int prevX = c.getX(), prevY = c.getY();

        // — Primer tramo: al NUEVO pedido —
        List<Point> pathToNew = buildManhattanPath(prevX, prevY, p.getX(), p.getY(), hora, contexto);
        if (pathToNew == null) return false;           // imposible alcanzar
        hora += pathToNew.size();                      // 1 paso = 1 minuto
        hora += 15;                                    // +15 min de descarga
        if (hora > p.getTiempoLimite()) return false;
        disponible -= p.getVolumen();
        if (disponible < 0) return false;

        // avanzamos “virtualmente” a la posición del pedido
        prevX = p.getX();
        prevY = p.getY();

        // — Siguientes tramos: los pedidos ya en rutaPendiente —
        for (Pedido orig : c.getPedidosCargados()) {
            List<Point> pathSeg = buildManhattanPath(prevX, prevY, orig.getX(), orig.getY(), hora, contexto);
            if (pathSeg == null) return false;         // no hay ruta libre
            hora += pathSeg.size();
            hora += 15;                                // tiempo de servicio
            if (hora > orig.getTiempoLimite()) return false;
            disponible -= orig.getVolumen();
            if (disponible < 0) return false;

            prevX = orig.getX();
            prevY = orig.getY();
        }

        return true;
    }

    private int posicionOptimaDeInsercion(CamionEstado c, Pedido pNuevo, int tiempoActual, ExecutionContext contexto) {
        List<Pedido> originales = c.getPedidosCargados();
        int mejorIdx = originales.size();
        int mejorHoraEntrega = Integer.MAX_VALUE;

        // Capacidad y posición de arranque reales del camión
        double capacidadOriginal = c.getCapacidadDisponible();
        int x0 = c.getX(), y0 = c.getY();

        // Probar cada posible posición de inserción
        for (int idx = 0; idx <= originales.size(); idx++) {
            double disponible = capacidadOriginal;
            int hora = tiempoActual;
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
                hora += path.size();
                // 3) Tiempo de servicio (descarga)
                hora += 15;
                // 4) Comprobar deadline
                if (hora > q.getTiempoLimite()) {
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
            if (valido && hora < mejorHoraEntrega) {
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

    private String turnoDeMinuto(int t) {
        int mod = t % 1440;
        if (mod < 480) return "T1";
        else if (mod < 960) return "T2";
        else return "T3";
    }


    public static int calcularTiempoAveria(String turnoActual, String tipoIncidente  ,int tiempoActual) {
        int inactividad = 0;

        switch (tipoIncidente) {
            case "T1":
                // Tipo 1: 2 horas en sitio (120 minutos)
                inactividad = 120;
                break;

            case "T2":
                // Tipo 2: 2 horas en sitio + 1 turno en taller
                inactividad = 120;  // Inmovilización inicial

                switch (turnoActual) {
                    case "T1":
                        // Disponible en turno 3 del mismo día
                        inactividad += (480 * 1);  // Turno 2 completo
                        break;
                    case "T2":
                        // Disponible en turno 1 del día siguiente
                        inactividad += (480 * 2);  // Turno 3 + Turno 1
                        break;
                    case "T3":
                        // Disponible en turno 2 del día siguiente
                        inactividad += (480 * 3);  // Turno 1 + Turno 2
                        break;
                }
                break;

            case "T3":
                // Tipo 3: 4 horas en sitio + 1 día completo en taller (día A+2, Turno 1)
                inactividad = 240;  // Inmovilización inicial

                int minutosRestantesDelDia = 1440 - (tiempoActual % 1440);
                inactividad += minutosRestantesDelDia; // Resto del día actual
                inactividad += 1440 * 2;     // Dos días completos más (Día A+1 y Día A+2)
                break;

            default:
                System.out.println("Tipo de incidente desconocido: " + tipoIncidente);
                break;
        }

        return inactividad;
    }

    public Averia registrarAveriaSimulacion(String simulationId,AveriaDTO dto) {
        ExecutionContext operationalContext = simulationManagerService.getContextoSimulacion(simulationId);
        if (operationalContext == null) {
            throw new IllegalStateException("Operational context is not available.");
        }
        String turno= dto.getTurno();
        String camionId= dto.getCodigoVehiculo();
        String tipoAveria= dto.getTipoIncidente();
        Averia nuevAveria= new Averia(turno,camionId,tipoAveria);
        operationalContext.getAveriasPorTurno()
                .computeIfAbsent(turno, k -> new java.util.HashMap<>()).put(camionId, tipoAveria);
        System.out.println("Camion " + camionId + " marked with averia: " + tipoAveria + " for turno " + turno);
        return nuevAveria;
    }


    private boolean isBlockedMove(int x, int y, int t, ExecutionContext estado) {
        for (Bloqueo b : estado.getBloqueos()) {
            // The logic for checking if a point is inside a polygonal block is complex.
            // Assuming a simplified check here. The actual logic resides in Bloqueo.estaBloqueado
            if (b.isActiveAt(t) && b.estaBloqueado(t, new java.awt.Point(x,y))) {
                 return true;
            }
        }
        return false;
    }

    private List<Point> findPathAStar(Point start, Point end, int startTime, ExecutionContext estado) {
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
                if (closedSet.contains(neighborPos) || isBlockedMove(neighborPos.x, neighborPos.y, startTime + currentNode.gCost + 1, estado)) {
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
    public List<Point> buildManhattanPath(int x1, int y1, int x2, int y2, int tiempoInicial, ExecutionContext estado) {
        List<Point> path = new ArrayList<>();
        Point current = new Point(x1, y1);
        int t = tiempoInicial;

        while (current.x != x2 || current.y != y2) {
            Point prev = new Point(current.x, current.y);
            if      (current.x < x2) current.x++;
            else if (current.x > x2) current.x--;
            else if (current.y < y2) current.y++;
            else                     current.y--;

            Point next = new Point(current.x, current.y);
            int tiempoLlegada = t + 1;

            if (isBlockedMove(next.x, next.y, tiempoLlegada, estado)) {
                // Invocar A* si hay bloqueo
                List<Point> alt = findPathAStar(prev, new Point(x2, y2), tiempoLlegada, estado);
                if (alt == null || alt.isEmpty()) {
                    System.err.printf("Error: No hay ruta de (%d,%d) a (%d,%d) en t+%d debido a bloqueos%n", 
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
}
