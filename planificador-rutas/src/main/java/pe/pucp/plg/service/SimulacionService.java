package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.dto.*;
import pe.pucp.plg.dto.enums.EventType;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.control.SimulationControlState;
import pe.pucp.plg.util.ResourceLoader;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Servicio responsable de la inicialización y ejecución de simulaciones.
 * Se encarga de la configuración inicial, la publicación de eventos y 
 * la ejecución asíncrona de la simulación completa.
 */
@Service
public class SimulacionService {
    
    private final OrchestratorService orchestratorService;
    private final SimulationManagerService simulationManagerService;
    private final EventPublisherService eventPublisher;

    private Future<?> activeSimulationTask;
    
    @Autowired
    public SimulacionService(OrchestratorService orchestratorService, 
                            SimulationManagerService simulationManagerService,
                            EventPublisherService eventPublisher) {
        this.orchestratorService = orchestratorService;
        this.simulationManagerService = simulationManagerService;
        this.eventPublisher = eventPublisher;
    }
    
    /**
     * Inicia una simulación básica o reinicia el contexto operacional.
     * @return El ID del contexto de simulación inicializado
     */
    public String iniciarSimulacion() {
        // Inicializa/reinicia el contexto operacional
        simulationManagerService.initializeOperationalContext();
        return "operational_context_reset_or_initialized";
    }
    
    /**
     * Inicia una simulación basada en los parámetros de la solicitud.
     * @param request La solicitud con los parámetros de simulación
     * @return Estado de la simulación inicializada
     */
    /**
     * Initiates a new simulation based on date range specified in the request.
     * @param request The request containing fecha inicio, duracion, and simulation name.
     * @return A DTO with the status and ID of the new simulation.
     */
    public SimulationStatusDTO iniciarSimulacion(SimulationRequest request) {
        try {
            // 1. Crear el contexto
            String simulationId = simulationManagerService.crearContextoSimulacion(request.isEsColapso());
            EventDTO eventoInicio = EventDTO.of(EventType.SIMULATION_STARTED, null); // No payload necesario o poner info básica
            eventPublisher.publicarEventoSimulacion(simulationId, eventoInicio);

            ExecutionContext currentSimContext = simulationManagerService.getActiveSimulationContext();
    
            if (currentSimContext == null) {
                throw new RuntimeException("No se pudo crear el contexto de simulación.");
            }
            
            // 2. Validar la fecha de inicio
            if (request.getFechaInicio() == null || request.getFechaInicio().isBlank()) {
                throw new IllegalArgumentException("La fecha de inicio es obligatoria.");
            }
            
            // 3. Convertir la fecha de inicio a LocalDate
            LocalDate fechaInicio = LocalDate.parse(request.getFechaInicio(), DateTimeFormatter.ISO_LOCAL_DATE);
            currentSimContext.setFechaInicio(fechaInicio); 
            currentSimContext.setDuracionDias(request.getDuracionDias()); 
            
            // 4. Cargar pedidos y bloqueos para el primer día
            List<Pedido> pedidosDiaUno = ResourceLoader.cargarPedidosParaFecha(fechaInicio);
            List<Bloqueo> bloqueosDiaUno = ResourceLoader.cargarBloqueosParaFecha(fechaInicio);
            
            // 5. Organizar los pedidos por tiempo e inicializar el tiempo actual
            LocalDateTime tiempoInicial = fechaInicio.atStartOfDay();
            currentSimContext.setCurrentTime(tiempoInicial);
            
            NavigableMap<LocalDateTime, List<Pedido>> pedidosPorTiempo = new TreeMap<>();
            for (Pedido p : pedidosDiaUno) {
                pedidosPorTiempo.computeIfAbsent(p.getTiempoCreacion(), k -> new ArrayList<>()).add(p);
            }
            currentSimContext.setPedidosPorTiempo(pedidosPorTiempo);
            
            // 6. Añadir los pedidos iniciales (tiempo 0) a la lista activa
            List<Pedido> initialPedidos = pedidosPorTiempo.getOrDefault(tiempoInicial, new ArrayList<>());
            currentSimContext.setPedidos(new ArrayList<>(initialPedidos));
            if (currentSimContext.getPedidosPorTiempo().containsKey(tiempoInicial)) {
                currentSimContext.getPedidosPorTiempo().remove(tiempoInicial);
            }
            
            // 7. Establecer los bloqueos iniciales
            for (Bloqueo b : bloqueosDiaUno) {
                currentSimContext.getBloqueosPorTiempo().computeIfAbsent(b.getStartTime(), k -> new ArrayList<>()).add(b);
                currentSimContext.getBloqueosPorDia().add(b);
            }
            
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
            System.err.println("Error starting simulation: " + e.getMessage());
            throw new RuntimeException("Error al iniciar simulación con request: " + e.getMessage(), e);
        }
    }

    /**
     * Obtiene el tiempo actual de una simulación específica.
     * @param simulationId El ID de la simulación
     * @return El tiempo actual de la simulación
     */
    public LocalDateTime getTiempoActual(String simulationId) {
        ExecutionContext currentContext = simulationManagerService.getActiveSimulationContext();
        if (currentContext == null) {
            throw new IllegalArgumentException("Simulation context not found for ID: " + simulationId);
        }
        return currentContext.getCurrentTime();
    }
    
    /**
     * Ejecuta una simulación completa de manera asíncrona.
     * Avanza minuto a minuto hasta completar la duración de la simulación.
     * @param simulationId El ID de la simulación a ejecutar
     */
    public void ejecutarSimulacionCompleta(String simulationId) {
        //No iniciar una nueva simulación si ya hay una activa
        if (activeSimulationTask != null && !activeSimulationTask.isDone()) {
            System.out.println("Ya hay una simulación activa. No se puede iniciar una nueva.");
            return;
        }

        // Ejecutar la simulación en un hilo separado
        this.activeSimulationTask = CompletableFuture.runAsync(() -> {
            try {
                ExecutionContext context = simulationManagerService.getActiveSimulationContext();
                SimulationControlState controlState = simulationManagerService.getActiveSimulationControlState();

                if (context == null || controlState == null) {
                    System.err.println("No se encontró el contexto o el estado de control de la simulación: " + simulationId);
                    return;
                }
                
                // Publicar evento de inicio de ejecución
                eventPublisher.publicarEventoSimulacion(simulationId, 
                    EventDTO.of(EventType.SIMULATION_RUNNING, null));
                
                // Calcular cuando debería terminar la simulación
                LocalDateTime fechaFin = context.getFechaInicio()
                    .plusDays(context.getDuracionDias())
                    .atTime(00, 00);
                
                System.out.println("Iniciando simulación completa desde " + context.getCurrentTime() + 
                                  " hasta " + fechaFin);
                
                // Ejecutar la simulación hasta que se alcance la fecha final
                while (simulationManagerService.getActiveSimulationContext() != null && context.getCurrentTime().isBefore(fechaFin)) {
                    while(controlState.isPaused()) {
                        Thread.sleep(300);
                    }

                    LocalDateTime colapsed = orchestratorService.stepOneMinute(context, simulationId);

                    // Si retorna null, es que ha colapsado
                    if (colapsed == null) {
                        detenerYLimpiarSimulacion(simulationId);
                    }

                    Thread.sleep(controlState.getStepDelayMs());
                }
                
                // Publicar evento de fin de simulación
                eventPublisher.publicarEventoSimulacion(simulationId, 
                    EventDTO.of(EventType.SIMULATION_COMPLETED, null));
                
                System.out.println("Simulación " + simulationId + " completada hasta " + context.getCurrentTime());
            } catch (Exception e) {
                System.err.println("Error al ejecutar simulación completa: " + e.getMessage());
                eventPublisher.publicarEventoSimulacion(simulationId, 
                    EventDTO.of(EventType.SIMULATION_ERROR, e.getMessage()));
            } finally {
                System.out.println("Finalizando ejecución de simulación " + simulationId);
                simulationManagerService.destruirContextoSimulacion(simulationId);
                activeSimulationTask = null; 
            }
        });
    }

    public void detenerYLimpiarSimulacion(String simulationId) {
        if (activeSimulationTask != null) {
            activeSimulationTask.cancel(true);
        }
        
        simulationManagerService.destruirContextoSimulacion(simulationId);
        
        activeSimulationTask = null; 
        
        System.out.println("🛑 Simulación " + simulationId + " detenida y limpiada por el usuario.");
    }
}