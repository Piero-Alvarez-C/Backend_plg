package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.CamionDTO;
import pe.pucp.plg.service.OperationService;

import java.util.List;

@RestController
@RequestMapping("/api/camiones")
public class CamionController {

    @Autowired
    private OperationService operationService;

    @GetMapping
    public ResponseEntity<List<CamionDTO>> listarCamiones() {
        List<CamionDTO> lista = operationService.getListaCamionesOperacionalesDTO();
        return ResponseEntity.ok(lista);
    }

    @PostMapping("/{id}/reset")
    public ResponseEntity<?> reset(@PathVariable String id) {
        boolean success = operationService.resetCamionOperacional(id);
        if (!success) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/paso")
    public ResponseEntity<?> avanzarPaso(@PathVariable String id) {
        boolean success = operationService.avanzarPasoCamionOperacional(id);
        if (!success) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/recargar")
    public ResponseEntity<?> recargar(@PathVariable String id) {
        boolean success = operationService.recargarCombustibleCamionOperacional(id);
        if (!success) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().build();
    }

}
