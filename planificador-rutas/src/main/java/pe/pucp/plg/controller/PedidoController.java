package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.PedidoDTO;
import pe.pucp.plg.model.Pedido;
import pe.pucp.plg.service.PlanificadorService;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pedidos")
@CrossOrigin(origins = "*")
public class PedidoController {

    @Autowired
    private PlanificadorService planificadorService;

    @GetMapping
    public List<PedidoDTO> listarPedidos() {
        List<Pedido> pedidos = planificadorService.getPedidos();
        return pedidos.stream()
                .map(p -> new PedidoDTO(
                        p.getFechaHoraCreacion(),
                        p.getIdCliente(),
                        p.getPosX(),
                        p.getPosY(),
                        p.getVolumen(),
                        p.getPlazoHoras()
                ))
                .collect(Collectors.toList());
    }
}
