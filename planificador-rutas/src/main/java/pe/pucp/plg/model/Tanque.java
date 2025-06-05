package pe.pucp.plg.model;

public class Tanque {
    private int posX;
    private int posY;
    private double capacidadTotal;
    private double capacidadDisponible;
    public Tanque(int x, int y, double cap) {
        this.posX = x;
        this.posY = y;
        this.capacidadTotal = cap;
        this.capacidadDisponible    = cap;
    }
    public int getPosX() { return posX; }
    public int getPosY() { return posY; }
    public double getCapacidadTotal() { return capacidadTotal; }
    public double getDisponible()    { return capacidadDisponible; }
    public void setDisponible(double d) { this.capacidadDisponible = d; }
}
