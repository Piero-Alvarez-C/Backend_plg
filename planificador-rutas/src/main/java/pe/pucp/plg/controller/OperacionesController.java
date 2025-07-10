package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.SimulacionSnapshotDTO;
import pe.pucp.plg.service.OperationService;

@RestController
@RequestMapping("/api/operaciones")
public class OperacionesController {

    private final OperationService operacionesService;

    @Autowired
    public OperacionesController(OperationService operacionesService) {
        this.operacionesService = operacionesService;
    }

    // Endpoint para resetear el contexto operativo
    @PostMapping("/reset")
    public ResponseEntity<String> resetOperacional() {
        operacionesService.resetOperacional();
        return ResponseEntity.ok("Contexto operativo reseteado");
    }

    // Endpoint para obtener el snapshot/estado actual del contexto operativo
    @GetMapping("/snapshot")
    public ResponseEntity<SimulacionSnapshotDTO> obtenerSnapshot() {
        return ResponseEntity.ok(operacionesService.getSnapshot());
    }
}
