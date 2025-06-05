package pe.pucp.plg.dto;

public class CamionDTO {
    private String id;
    private int x;
    private int y;
    private double disponible;
    private double combustibleDisponible;
    private String status;
    private double consumoAcumulado;

    // Getters y Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public double getDisponible() { return disponible; }
    public void setDisponible(double disponible) { this.disponible = disponible; }

    public double getCombustibleDisponible() { return combustibleDisponible; }
    public void setCombustibleDisponible(double combustibleDisponible) {
        this.combustibleDisponible = combustibleDisponible;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getConsumoAcumulado() { return consumoAcumulado; }
    public void setConsumoAcumulado(double consumoAcumulado) {
        this.consumoAcumulado = consumoAcumulado;
    }
}
