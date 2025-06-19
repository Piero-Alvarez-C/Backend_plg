package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.TanqueDTO;
import pe.pucp.plg.service.OperationService;

import java.util.List;

@RestController
@RequestMapping("/api/tanques")
public class TanqueController {

    @Autowired
    private OperationService operationService;

    @GetMapping
    public ResponseEntity<List<TanqueDTO>> obtenerTanques() {
        List<TanqueDTO> lista = operationService.getTanquesOperacionalesDTO();
        return ResponseEntity.ok(lista);
    }
}