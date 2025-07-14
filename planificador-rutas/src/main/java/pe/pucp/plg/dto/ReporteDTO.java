package pe.pucp.plg.dto;

public class ReporteDTO {
    private int totalPedidosEntregados;
    private double totalDistanciaRecorrida;
    private String pedidoColapso;

    public ReporteDTO(int totalPedidosEntregados, double totalDistanciaRecorrida, String pedidoColapso) {
        this.totalPedidosEntregados = totalPedidosEntregados;
        this.totalDistanciaRecorrida = totalDistanciaRecorrida;
        this.pedidoColapso = pedidoColapso;
    }

    public int getTotalPedidosEntregados() {
        return totalPedidosEntregados;
    }

    public double getTotalDistanciaRecorrida() {
        return totalDistanciaRecorrida;
    }

    public String getPedidoColapso() {
        return pedidoColapso;
    }
}
