package pe.pucp.plg.dto;

import java.util.List;

public class RutaDTO {
    /** Estado del camión usado en esta ruta */
    private CamionEstadoDTO estadoCamion;
    /** IDs de los pedidos que atiende en esta ruta */
    private List<Integer> pedidos;
    /** Distancia total de la ruta (mismo campo en el modelo) */
    private double distancia;
    /** Consumo total estimado en la ruta */
    private double consumo;

    // Getters / Setters
    public CamionEstadoDTO getEstadoCamion() { return estadoCamion; }
    public void setEstadoCamion(CamionEstadoDTO estadoCamion) { this.estadoCamion = estadoCamion; }

    public List<Integer> getPedidos() { return pedidos; }
    public void setPedidos(List<Integer> pedidos) { this.pedidos = pedidos; }

    public double getDistancia() { return distancia; }
    public void setDistancia(double distancia) { this.distancia = distancia; }

    public double getConsumo() { return consumo; }
    public void setConsumo(double consumo) { this.consumo = consumo; }
}
