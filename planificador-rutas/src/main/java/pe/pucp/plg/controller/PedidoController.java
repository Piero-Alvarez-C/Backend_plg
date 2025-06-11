package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.PedidoDTO;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.context.SimulacionEstado;
import pe.pucp.plg.util.MapperUtil;

import java.util.List;
import java.util.stream.Collectors;

@CrossOrigin(origins = "http://localhost:3000")
@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    @Autowired
    private SimulacionEstado simulacionEstado;

    @GetMapping
    public ResponseEntity<List<PedidoDTO>> listarPedidos() {
        List<PedidoDTO> lista = simulacionEstado.getPedidos().stream()
                .map(MapperUtil::toPedidoDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(lista);
    }

    @PostMapping
    public ResponseEntity<PedidoDTO> crearPedido(@RequestBody PedidoDTO dto) {
        int id = simulacionEstado.generateUniquePedidoId();
        Pedido nuevo = new Pedido(id,
                simulacionEstado.getCurrentTime(),
                dto.getX(), dto.getY(),
                dto.getVolumen(), dto.getTiempoLimite());
        simulacionEstado.getPedidos().add(nuevo);
        return ResponseEntity.ok(MapperUtil.toPedidoDTO(nuevo));
    }
}
