package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.CamionDTO;
import pe.pucp.plg.model.Camion;
import pe.pucp.plg.service.CamionService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/camiones")
@CrossOrigin(origins = "*") // permite conexión desde cualquier origen
public class CamionController {

    @Autowired
    private CamionService camionService;

    @GetMapping
    public List<CamionDTO> listarCamiones() {
        List<Camion> flota = camionService.getFlota();
        return flota.stream()
                .map(c -> new CamionDTO(
                        c.getCodigo(),
                        c.getTipo(),
                        c.getCapacidadM3(),
                        c.getPesoTotalTon()))
                .collect(Collectors.toList());
    }
}
