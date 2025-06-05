package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.BloqueoDTO;
import pe.pucp.plg.dto.CamionDTO;
import pe.pucp.plg.dto.PedidoDTO;
import pe.pucp.plg.dto.TanqueDTO;
import pe.pucp.plg.model.Camion;
import pe.pucp.plg.model.Tanque;
import pe.pucp.plg.service.CamionService;
import pe.pucp.plg.service.SimulacionService;
import pe.pucp.plg.service.TanqueService;
import pe.pucp.plg.state.SimulacionEstado;
import pe.pucp.plg.util.MapperUtil;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/simulacion")
public class SimulacionController {

    @Autowired
    private SimulacionService simulacionService;

    @Autowired
    private SimulacionEstado estado;

    // ------------------------------------------------------------
    // 1) Reiniciar toda la simulación
    // ------------------------------------------------------------
    @PostMapping("/reset")
    public ResponseEntity<?> resetear() {
        simulacionService.iniciarSimulacion();
        return ResponseEntity.ok("Simulación reiniciada");
    }

    // ------------------------------------------------------------
    // 2) Avanzar un minuto de simulación
    // ------------------------------------------------------------
    @PostMapping("/step")
    public ResponseEntity<Integer> step() {
        int nuevoTiempo = simulacionService.stepOneMinute();
        return ResponseEntity.ok(nuevoTiempo);
    }

    // ------------------------------------------------------------
    // 3) Obtener tiempo actual
    // ------------------------------------------------------------
    @GetMapping("/time")
    public ResponseEntity<Integer> getTime() {
        return ResponseEntity.ok(simulacionService.getTiempoActual());
    }

    // ------------------------------------------------------------
    // 4) Obtener lista de camiones (DTO)
    // ------------------------------------------------------------
    @GetMapping("/camiones")
    public ResponseEntity<List<CamionDTO>> listarCamiones() {
        List<CamionDTO> lista = estado.getCamiones().stream()
                .map(MapperUtil::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }

    // ------------------------------------------------------------
    // 5) Obtener lista de tanques (DTO)
    // ------------------------------------------------------------
    @GetMapping("/tanques")
    public ResponseEntity<List<TanqueDTO>> listarTanques() {
        List<TanqueDTO> lista = estado.getTanques().stream()
                .map(MapperUtil::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }

    // ------------------------------------------------------------
    // 6) Obtener lista de pedidos (DTO)
    // ------------------------------------------------------------
    @GetMapping("/pedidos")
    public ResponseEntity<List<PedidoDTO>> listarPedidos() {
        List<PedidoDTO> lista = estado.getPedidos().stream()
                .map(MapperUtil::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }

    // ------------------------------------------------------------
    // 7) Obtener lista de bloqueos (DTO)
    // ------------------------------------------------------------
    @GetMapping("/bloqueos")
    public ResponseEntity<List<BloqueoDTO>> listarBloqueos() {
        List<BloqueoDTO> lista = estado.getBloqueos().stream()
                .map(MapperUtil::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }
}
