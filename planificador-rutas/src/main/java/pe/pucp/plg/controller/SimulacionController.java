package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import pe.pucp.plg.dto.*;
import pe.pucp.plg.service.SimulacionService;
import pe.pucp.plg.service.SimulationManagerService; 
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
    // 3) Obtener tiempo actual de simulación específica
    // ------------------------------------------------------------
    @GetMapping("/{simulationId}/time")
    public ResponseEntity<LocalDateTime> getTime(@PathVariable String simulationId) {
        try {
            LocalDateTime tiempoActual = simulacionService.getTiempoActual(simulationId);
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
            ExecutionContext context = simulationManagerService.getActiveSimulationContext();
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

    @PostMapping("/{simulationId}/run")
    public ResponseEntity<Void> runSimulation(@PathVariable String simulationId) {
        simulacionService.ejecutarSimulacionCompleta(simulationId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/pause")
    public ResponseEntity<Void> pauseSimulation() {
        simulationManagerService.pauseActiveSimulation();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/resume")
    public ResponseEntity<Void> resumeSimulation() {
        simulationManagerService.resumeActiveSimulation();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/speed")
    public ResponseEntity<Void> setSimulationSpeed(@RequestBody SpeedRequest speedRequest) {
        simulationManagerService.setSpeedOfActiveSimulation(speedRequest.getDelayMs());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{simulationId}/stop")
    public ResponseEntity<Void> stopSimulation(@PathVariable String simulationId) {
        simulacionService.detenerYLimpiarSimulacion(simulationId); 
        return ResponseEntity.ok().build();
    }

}
