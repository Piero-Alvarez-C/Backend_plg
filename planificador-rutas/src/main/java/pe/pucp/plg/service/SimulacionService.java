package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.dto.SimulationRequest;
import pe.pucp.plg.dto.SimulationStatusDTO;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.service.algorithm.ACOPlanner;
import pe.pucp.plg.util.ParseadorArchivos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SimulacionService {

    private final SimulationManagerService simulationManagerService;
    private final ACOPlanner acoPlanner;
    private final ArchivoService archivoService;

    @Autowired
    public SimulacionService(SimulationManagerService simulationManagerService,
                             ArchivoService archivoService) {
        this.simulationManagerService = simulationManagerService;
        this.acoPlanner = new ACOPlanner(); // Instantiate as POJO
        this.archivoService = archivoService;
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
        ExecutionContext currentContext = simulationManagerService.getContextoSimulacion(simulationId);
        if (currentContext == null) {
            // If simulationId refers to the operational context, get it.
            if ("operational".equals(simulationId)) { // Or some other agreed-upon ID for operational
                currentContext = simulationManagerService.getOperationalContext();
            } else {
                throw new IllegalArgumentException("Simulation context not found for ID: " + simulationId);
            }
        }
        // Pass the context to ACOPlanner's stepOneMinute method
        return acoPlanner.stepOneMinute(currentContext); 
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
     * Initiates a new simulation based on input files specified in the request.
     * @param request The request containing file IDs and simulation name.
     * @return A DTO with the status and ID of the new simulation.
     */
    public SimulationStatusDTO iniciarSimulacion(SimulationRequest request) {
        try {
            // 1. Create a new simulation context via the manager.
            String simulationId = simulationManagerService.crearContextoSimulacion();
            ExecutionContext currentSimContext = simulationManagerService.getContextoSimulacion(simulationId);

            if (currentSimContext == null) {
                // This case should ideally not happen if crearContextoSimulacion was successful
                // and returned a valid ID that getContextoSimulacion can immediately find.
                throw new RuntimeException("Failed to retrieve newly created simulation context: " + simulationId);
            }

            // 2. Load data from files specified in the request.
            String contenidoPedidos = new String(archivoService.obtenerArchivo(request.getFileIdPedidos()), StandardCharsets.UTF_8);
            String contenidoBloqueos = new String(archivoService.obtenerArchivo(request.getFileIdBloqueos()), StandardCharsets.UTF_8);
            String contenidoAverias = new String(archivoService.obtenerArchivo(request.getFileIdAverias()), StandardCharsets.UTF_8);

            // 3. Parse and populate the currentSimContext with data from files.
            Map<Integer, List<Pedido>> pedidosPorTiempo = ParseadorArchivos.parsearPedidosPorTiempo(contenidoPedidos);
            currentSimContext.setPedidosPorTiempo(pedidosPorTiempo);
            
            List<Pedido> initialPedidosFromFile = pedidosPorTiempo.getOrDefault(0, new ArrayList<>());
            currentSimContext.setPedidos(new ArrayList<>(initialPedidosFromFile)); 
            if (currentSimContext.getPedidosPorTiempo().containsKey(0)) {
                 currentSimContext.getPedidosPorTiempo().remove(0); 
            }

            List<Bloqueo> bloqueos = ParseadorArchivos.parsearBloqueos(contenidoBloqueos);
            currentSimContext.setBloqueos(bloqueos);

            Map<String, Map<String, String>> averiasPorTurno = ParseadorArchivos.parsearAverias(contenidoAverias);
            currentSimContext.setAveriasPorTurno(averiasPorTurno);
            
            // Camiones and Tanques are initialized by the manager. 
            // If the request implies a different fleet/tank setup (e.g., from other files),
            // additional logic would be needed here to modify the context.

            // Ensure lists that should start empty for this specific simulation are cleared.
            currentSimContext.getEventosEntrega().clear();
            currentSimContext.getAveriasAplicadas().clear();
            currentSimContext.getCamionesInhabilitados().clear();
            currentSimContext.setRutas(new ArrayList<>()); // Clear any default/previous routes

            // 4. Prepare response DTO.
            String nombre = request.getNombreSimulacion() != null ? request.getNombreSimulacion() : "Simulación " + simulationId.substring(0, 8); // Shortened ID for name
            SimulationStatusDTO status = new SimulationStatusDTO();
            status.setSimulationId(simulationId);
            status.setNombreSimulacion(nombre);
            status.setEstado("INITIALIZED"); 
            status.setAvance(0);

            return status;

        } catch (Exception e) {
            throw new RuntimeException("Error al iniciar simulación con request: " + e.getMessage(), e);
        }
    }
}
