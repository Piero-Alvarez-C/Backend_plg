package pe.pucp.plg.model.common;

public class EntregaEvent {
    public int time;             // minuto de disparo
    private final String camionId;   // Changed from CamionEstado camion
    private final Pedido pedido; // Changed to be final
    
    public EntregaEvent(int time, String camionId, Pedido pedido) {
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
