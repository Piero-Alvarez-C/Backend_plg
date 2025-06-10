package pe.pucp.plg.dto;

public class TanqueDTO {
    private int x;
    private int y;
    private double capacidadTotal;
    private double capacidadDisponible;

    // Getters y setters
    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public double getCapacidadTotal() { return capacidadTotal; }
    public void setCapacidadTotal(double capacidadTotal) { this.capacidadTotal = capacidadTotal; }

    public double getCapacidadDisponible() { return capacidadDisponible; }
    public void setCapacidadDisponible(double capacidadDisponible) { this.capacidadDisponible = capacidadDisponible; }
}
