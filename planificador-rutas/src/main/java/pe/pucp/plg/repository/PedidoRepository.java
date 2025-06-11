package pe.pucp.plg.repository;

import org.springframework.stereotype.Repository;
import pe.pucp.plg.model.common.Pedido;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
public class PedidoRepository {

    private final List<Pedido> pedidos = new ArrayList<>();
    private final AtomicInteger secuenciaId = new AtomicInteger(1000); // ID incremental

    // Agregar un solo pedido
    public void agregar(Pedido pedido) {
        if (pedido.getId() == 0) {
            pedido.setId(secuenciaId.getAndIncrement());
        }
        pedidos.add(pedido);
    }

    // Agregar múltiples pedidos
    public void agregarTodos(List<Pedido> nuevos) {
        for (Pedido pedido : nuevos) {
            if (pedido.getId() == 0) {
                pedido.setId(secuenciaId.getAndIncrement());
            }
            pedidos.add(pedido);
        }
    }

    // Obtener todos los pedidos
    public List<Pedido> getAll() {
        return Collections.unmodifiableList(pedidos);
    }

    // Reiniciar todos los pedidos
    public void reset() {
        pedidos.clear();
        secuenciaId.set(1000);
    }
}
