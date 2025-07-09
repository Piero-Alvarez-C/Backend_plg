package pe.pucp.plg.model.common;

import java.time.LocalDateTime;

public class EntregaEvent {
    public LocalDateTime time;   // tiempo de disparo
    private final String camionId;   // Changed from CamionEstado camion
    private final Pedido pedido; // Changed to be final
    
    public EntregaEvent(LocalDateTime time, String camionId, Pedido pedido) {
        this.time = time;
        this.camionId = camionId;
        this.pedido = pedido; 
    }

    // Getter for camionId
    public String getCamionId() {
        return camionId;
    }

    // Getter for Pedido
    public Pedido getPedido() {
        return pedido;
    }
}
