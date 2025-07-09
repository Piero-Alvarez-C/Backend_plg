package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.EventDTO;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.service.OperationService;
import pe.pucp.plg.service.ArchivoService;

import java.util.logging.Logger;

@RestController
@RequestMapping("/api/v1/operaciones")
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
    public ResponseEntity<ExecutionContext> obtenerSnapshot() {
        return ResponseEntity.ok(operacionesService.getSnapshot());
    }
}
