package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.CamionDTO;
import pe.pucp.plg.model.state.CamionDinamico;
import pe.pucp.plg.service.CamionService;
import pe.pucp.plg.model.context.SimulacionEstado;
import pe.pucp.plg.util.MapperUtil;

import java.util.List;
import java.util.stream.Collectors;

@CrossOrigin(origins = "http://localhost:3000")
@RestController
@RequestMapping("/api/camiones")
public class CamionController {

    @Autowired
    private CamionService camionService;

    @Autowired
    private SimulacionEstado simulacionEstado;

    @GetMapping
    public ResponseEntity<List<CamionDTO>> listarCamiones() {
        List<CamionDTO> lista = camionService.inicializarFlota().stream()
                .map(MapperUtil::toCamionDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }


    @PostMapping("/{id}/reset")
    public ResponseEntity<?> reset(@PathVariable String id) {
        CamionDinamico camion = buscarPorId(id);
        if (camion == null) return ResponseEntity.notFound().build();
        camionService.reset(camion);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/paso")
    public ResponseEntity<?> avanzarPaso(@PathVariable String id) {
        CamionDinamico camion = buscarPorId(id);
        if (camion == null) return ResponseEntity.notFound().build();
        camionService.avanzarUnPaso(camion);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/recargar")
    public ResponseEntity<?> recargar(@PathVariable String id) {
        CamionDinamico camion = buscarPorId(id);
        if (camion == null) return ResponseEntity.notFound().build();
        camionService.recargarCombustible(camion);
        return ResponseEntity.ok().build();
    }

    private CamionDinamico buscarPorId(String id) {
        return simulacionEstado.getCamiones().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElse(null);
    }
}
