package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.BloqueoDTO;
import pe.pucp.plg.model.Bloqueo;
import pe.pucp.plg.state.SimulacionEstado;
import pe.pucp.plg.util.MapperUtil;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bloqueos")
public class BloqueoController {
    @Autowired
    private SimulacionEstado simulacionEstado;

    @GetMapping
    public ResponseEntity<List<BloqueoDTO>> listarBloqueos() {
        List<Bloqueo> bloqueos = simulacionEstado.getBloqueos();
        List<BloqueoDTO> lista = bloqueos.stream()
                .map(MapperUtil::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }
}
