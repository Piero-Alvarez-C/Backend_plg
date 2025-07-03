package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.service.OperationService;
import pe.pucp.plg.util.MapperUtil;
import pe.pucp.plg.dto.AveriaDTO;
import pe.pucp.plg.model.common.Averia;

//import java.util.List;

@RestController
@RequestMapping("/api/averias")
public class AveriaController {

    @Autowired
    private OperationService operationService;

    /*@GetMapping
    public ResponseEntity<List<AveriaDTO>> listarAverias() {
        List<AveriaDTO> lista = operationService.getAveriasOperacionalesDTO();
        return ResponseEntity.ok(lista);
    }*/

    @PostMapping
    public ResponseEntity<AveriaDTO> agregarAveria(@RequestBody AveriaDTO dto) {
        Averia nuevaAveria = operationService.registrarAveriaCamion(dto);
        if (nuevaAveria == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(MapperUtil.toAveriaDTO(nuevaAveria));
    }
}