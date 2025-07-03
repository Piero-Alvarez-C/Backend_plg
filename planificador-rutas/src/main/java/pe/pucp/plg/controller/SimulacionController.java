package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.*;
import pe.pucp.plg.service.SimulacionService;
import pe.pucp.plg.service.SimulationManagerService;
import pe.pucp.plg.model.common.Averia;
import pe.pucp.plg.model.context.ExecutionContext; 
import pe.pucp.plg.util.MapperUtil;

@RestController
@RequestMapping("/api/simulacion")
public class SimulacionController {

    private final SimulacionService simulacionService;
    private final SimulationManagerService simulationManagerService; 

    @Autowired
    public SimulacionController(SimulacionService simulacionService, SimulationManagerService simulationManagerService) {
        this.simulacionService = simulacionService;
        this.simulationManagerService = simulationManagerService;
    }

    // ------------------------------------------------------------
    // 1) Reiniciar el contexto operacional (o una simulación base)
    // ------------------------------------------------------------
    @PostMapping("/reset")
    public ResponseEntity<?> resetearOperacional() { 
        // SimulacionService.iniciarSimulacion() is expected to handle operational context reset/init
        String result = simulacionService.iniciarSimulacion(); 
        return ResponseEntity.ok("Resultado: " + result);
    }

    // ------------------------------------------------------------
    // 2) Avanzar un minuto de simulación específica
    // ------------------------------------------------------------
    @PostMapping("/{simulationId}/step")
    public ResponseEntity<SimulacionSnapshotDTO> step(@PathVariable String simulationId) {
        // Step the simulation
        int nuevoTiempo = simulacionService.stepOneMinute(simulationId);

        // Get the updated context AFTER the step to create the snapshot
        ExecutionContext simContext = simulationManagerService.getContextoSimulacion(simulationId);
        if (simContext == null) {
            // Attempt to get operational context if simulationId suggests it
            if ("operational".equals(simulationId)) { // Or a constant for operational context ID
                simContext = simulationManagerService.getOperationalContext();
            }
            if (simContext == null) {
                 return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null); // Or a more descriptive error DTO
            }
        }
        
        // Ensure the context time matches nuevoTiempo, though simContext.getCurrentTime() should be authoritative after step
        if(simContext.getCurrentTime() != nuevoTiempo) {
            // Log potential inconsistency or decide which time is authoritative
            System.err.println("Time mismatch after step: service returned " + nuevoTiempo + ", context has " + simContext.getCurrentTime());
        }

        // Map to SnapshotDTO using the current state of simContext
        SimulacionSnapshotDTO snapshot = MapperUtil.toSnapshotDTO(simContext);
        // Override tiempoActual in snapshot if needed, though toSnapshotDTO should use context's time
        // snapshot.setTiempoActual(simContext.getCurrentTime()); 

        return ResponseEntity.ok(snapshot);
    }

    // ------------------------------------------------------------
    // 3) Obtener tiempo actual de simulación específica
    // ------------------------------------------------------------
    @GetMapping("/{simulationId}/time")
    public ResponseEntity<Integer> getTime(@PathVariable String simulationId) {
        try {
            int tiempoActual = simulacionService.getTiempoActual(simulationId);
            return ResponseEntity.ok(tiempoActual);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
    }

    // ------------------------------------------------------------
    // 4) Iniciar una nueva simulación específica (basada en archivos)
    // ------------------------------------------------------------
    @PostMapping("/start")
    public ResponseEntity<SimulationStatusDTO> iniciarNuevaSimulacion(@RequestBody SimulationRequest request) {
        try {
            SimulationStatusDTO status = simulacionService.iniciarSimulacion(request);
            return ResponseEntity.ok(status);
        } catch (RuntimeException e) {
            System.err.println("Error starting simulation: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null); 
        }
    }

    // ------------------------------------------------------------
    // 5) Obtener el snapshot de una simulación específica
    // ------------------------------------------------------------
    @GetMapping("/{simulationId}/snapshot")
    public ResponseEntity<SimulacionSnapshotDTO> getSnapshot(@PathVariable String simulationId) {
        try {
            ExecutionContext context = simulationManagerService.getContextoSimulacion(simulationId);
            if (context == null) {
                throw new IllegalArgumentException("Simulación no encontrada con ID: " + simulationId);
            }
            SimulacionSnapshotDTO snapshot = MapperUtil.toSnapshotDTO(context);
            return ResponseEntity.ok(snapshot);
        } catch (Exception e) {
            System.err.println("Error getting snapshot: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping("/{simulationId}/averia")
    public ResponseEntity<AveriaDTO> aplicarAveriaSim(@PathVariable String simulationId,@RequestBody AveriaDTO dto) {
        Averia nuevaAveria = simulacionService.registrarAveriaSimulacion(simulationId, dto);
        if (nuevaAveria == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(MapperUtil.toAveriaDTO(nuevaAveria));
    }

    
}
