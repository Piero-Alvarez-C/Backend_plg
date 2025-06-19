package pe.pucp.plg.model.common;

import java.util.ArrayList;
import java.util.List;

public class Ruta {
    private final String camionId;
    private List<Integer> pedidoIds = new ArrayList<>(); 
    public double distancia = 0;
    public double consumo = 0;

    // Constructor
    public Ruta(String camionId) {
        this.camionId = camionId;
    }

    // Getter for camionId
    public String getCamionId() {
        return camionId;
    }

    // Getter for pedidoIds
    public List<Integer> getPedidoIds() {
        return pedidoIds;
    }

    public void addPedidoId(Integer pedidoId) {
        this.pedidoIds.add(pedidoId);
    }

    public void setPedidoIds(List<Integer> pedidoIds) {
        this.pedidoIds = new ArrayList<>(pedidoIds); 
    }
}
