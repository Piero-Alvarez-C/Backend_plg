package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.PedidoDTO;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.service.OperationService;
import pe.pucp.plg.util.MapperUtil;

import java.util.List;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    @Autowired
    private OperationService operationService;

    @GetMapping
    public ResponseEntity<List<PedidoDTO>> listarPedidos() {
        List<PedidoDTO> lista = operationService.getPedidosOperacionalesDTO();
        return ResponseEntity.ok(lista);
    }

    @PostMapping
    public ResponseEntity<PedidoDTO> crearPedido(@RequestBody PedidoDTO dto) {
        Pedido nuevoPedido = operationService.crearNuevoPedidoOperacional(dto);
        if (nuevoPedido == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(MapperUtil.toPedidoDTO(nuevoPedido));
    }
}
