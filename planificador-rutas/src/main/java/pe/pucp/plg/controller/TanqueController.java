package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.TanqueDTO;
import pe.pucp.plg.model.Tanque;
import pe.pucp.plg.service.TanqueService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tanques")
@CrossOrigin(origins = "*")
public class TanqueController {

    @Autowired
    private TanqueService tanqueService;

    @GetMapping
    public List<TanqueDTO> obtenerTanques() {
        List<Tanque> lista = tanqueService.obtenerTanques();
        return lista.stream()
                .map(t -> new TanqueDTO(
                        t.getNombre(),
                        t.getPosX(),
                        t.getPosY(),
                        t.getCapacidadM3(),
                        t.isPrincipal()
                ))
                .collect(Collectors.toList());
    }
}