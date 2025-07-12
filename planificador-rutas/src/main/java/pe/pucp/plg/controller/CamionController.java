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

}
