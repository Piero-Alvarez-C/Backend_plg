package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.BloqueoDTO;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.context.SimulacionEstado;
import pe.pucp.plg.util.MapperUtil;

import java.util.List;
import java.util.stream.Collectors;

@CrossOrigin(origins = "http://localhost:3000")
@RestController
@RequestMapping("/api/bloqueos")
public class BloqueoController {
    @Autowired
    private SimulacionEstado simulacionEstado;

    @GetMapping
    public ResponseEntity<List<BloqueoDTO>> listarBloqueos() {
        List<Bloqueo> bloqueos = simulacionEstado.getBloqueos();
        List<BloqueoDTO> lista = bloqueos.stream()
                .map(MapperUtil::toBloqueoDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }
}
