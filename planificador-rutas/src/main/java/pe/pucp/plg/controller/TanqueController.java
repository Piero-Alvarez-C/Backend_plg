package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.TanqueDTO;
import pe.pucp.plg.model.context.SimulacionEstado;
import pe.pucp.plg.util.MapperUtil;

import java.util.List;
import java.util.stream.Collectors;

@CrossOrigin(origins = "http://localhost:3000")
@RestController
@RequestMapping("/api/tanques")
public class TanqueController {

    @Autowired
    private SimulacionEstado simulacionEstado;

    @GetMapping
    public ResponseEntity<List<TanqueDTO>> obtenerTanques() {
        List<TanqueDTO> lista = simulacionEstado.getTanques().stream()
                .map(MapperUtil::toTanqueDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }
}