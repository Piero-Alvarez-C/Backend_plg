package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.BloqueoDTO;
import pe.pucp.plg.service.OperationService;

import java.util.List;

@RestController
@RequestMapping("/api/bloqueos")
public class BloqueoController {
    @Autowired
    private OperationService operationService;

    @GetMapping
    public ResponseEntity<List<BloqueoDTO>> listarBloqueos() {
        List<BloqueoDTO> lista = operationService.getBloqueosOperacionalesDTO();
        return ResponseEntity.ok(lista);
    }
}
