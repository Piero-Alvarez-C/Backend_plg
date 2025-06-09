package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.PedidoDTO;
import pe.pucp.plg.model.Pedido;
import pe.pucp.plg.service.PlanificadorService;
import pe.pucp.plg.state.SimulacionEstado;
import pe.pucp.plg.util.MapperUtil;

import java.util.List;
import java.util.stream.Collectors;

@CrossOrigin(origins = "http://localhost:3000")
@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    @Autowired
    private PlanificadorService planificadorService;

    @Autowired
    private SimulacionEstado simulacionEstado;

    @GetMapping
    public ResponseEntity<List<PedidoDTO>> listarPedidos() {
        List<PedidoDTO> lista = simulacionEstado.getPedidos().stream()
                .map(MapperUtil::toPedidoDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }
}
