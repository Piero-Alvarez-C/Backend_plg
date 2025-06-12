package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.*;
import pe.pucp.plg.service.SimulacionService;
import pe.pucp.plg.model.context.SimulacionEstado;
import pe.pucp.plg.util.MapperUtil;

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
    public ResponseEntity<SimulacionSnapshotDTO> step() {
        int nuevoTiempo = simulacionService.stepOneMinute();

        SimulacionSnapshotDTO snapshot = new SimulacionSnapshotDTO();
        snapshot.setTiempoActual(nuevoTiempo);

        snapshot.setCamiones(
                estado.getCamiones().stream()
                        .map(MapperUtil::toCamionDTO)
                        .collect(Collectors.toList())
        );

        snapshot.setPedidos(
                estado.getPedidos().stream()
                        .filter(p -> !p.isAtendido() && !p.isDescartado())
                        .map(MapperUtil::toPedidoDTO)
                        .collect(Collectors.toList())
        );

        snapshot.setBloqueos(
                estado.getBloqueos().stream()
                        .map(MapperUtil::toBloqueoDTO)
                        .collect(Collectors.toList())
        );

        snapshot.setTanques(
                estado.getTanques().stream()
                        .map(MapperUtil::toTanqueDTO)
                        .collect(Collectors.toList())
        );

        snapshot.setRutas(
                estado.getRutas().stream()
                        .map(MapperUtil::toRutaDTO)
                        .collect(Collectors.toList())
        );

        return ResponseEntity.ok(snapshot);
    }
    // ------------------------------------------------------------
    // 3) Obtener tiempo actual
    // ------------------------------------------------------------
    @GetMapping("/time")
    public ResponseEntity<Integer> getTime() {
        return ResponseEntity.ok(estado.getCurrentTime());
    }


    @PostMapping("/start")
    public ResponseEntity<SimulationStatusDTO> iniciarSimulacion(@RequestBody SimulationRequest request) {
        SimulationStatusDTO estado = simulacionService.iniciarSimulacion(request);
        return ResponseEntity.ok(estado);
    }
}
