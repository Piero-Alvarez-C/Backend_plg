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
        List<Pedido> pedidos = planificadorService.getPedidos(); // asumimos que se expone esta lista
        return pedidos.stream()
                .map(p -> new PedidoDTO(
                        p.getIdCliente(), // reemplazar por el método correcto
                        p.getPosX(),         // coordenada X
                        p.getPosY(),         // coordenada Y
                        p.getVolumen(),   // volumen en m3
                        p.getPlazoHoras() // plazo de entrega
                ))
                .collect(Collectors.toList());
    }
}
