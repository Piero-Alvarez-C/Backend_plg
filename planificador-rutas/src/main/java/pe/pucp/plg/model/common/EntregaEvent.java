package pe.pucp.plg.model.common;

import pe.pucp.plg.model.state.CamionDinamico;

public class EntregaEvent {
    public int time;             // minuto de disparo
    public CamionDinamico camion;        // instancia real de Camion
    public Pedido pedido;        // instancia real de Pedido
    public EntregaEvent(int time, CamionDinamico camion, Pedido pedido) {
        this.time = time;
        this.camion = camion;
        this.pedido = pedido;
    }
}
