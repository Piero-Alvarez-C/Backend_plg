package pe.pucp.plg.model;

public class EntregaEvent {
    public int time;             // minuto de disparo
    public Camion camion;        // instancia real de Camion
    public Pedido pedido;        // instancia real de Pedido
    public EntregaEvent(int time, Camion camion, Pedido pedido) {
        this.time = time;
        this.camion = camion;
        this.pedido = pedido;
    }
}
