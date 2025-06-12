package pe.pucp.plg.dto;

public class TanqueDTO {
    private int posX;
    private int posY;
    private double capacidadTotal;
    private double capacidadDisponible;

    // Getters y setters

    public int getPosX() { return posX; }
    public void setPosX(int posX) { this.posX = posX; }

    public int getPosY() { return posY; }
    public void setPosY(int posY) { this.posY = posY; }

    public double getCapacidadTotal() { return capacidadTotal; }
    public void setCapacidadTotal(double capacidadTotal) { this.capacidadTotal = capacidadTotal; }

    public double getCapacidadDisponible() { return capacidadDisponible; }
    public void setCapacidadDisponible(double capacidadDisponible) { this.capacidadDisponible = capacidadDisponible; }
}
