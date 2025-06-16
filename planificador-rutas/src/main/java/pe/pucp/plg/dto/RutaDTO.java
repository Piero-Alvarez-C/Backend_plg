package pe.pucp.plg.dto;

import java.util.List;

public class RutaDTO {
    private String camionId;
    private List<Integer> pedidos;
    private double distancia;
    private double consumo;

    // Getters / Setters
    public String getCamionId() { return camionId; }
    public void setCamionId(String camionId) { this.camionId = camionId; }

    public List<Integer> getPedidos() { return pedidos; }
    public void setPedidos(List<Integer> pedidos) { this.pedidos = pedidos; }

    public double getDistancia() { return distancia; }
    public void setDistancia(double distancia) { this.distancia = distancia; }

    public double getConsumo() { return consumo; }
    public void setConsumo(double consumo) { this.consumo = consumo; }
}
