package pe.pucp.plg.model;

public class Tanque {
    private String nombre;
    private int posX;
    private int posY;
    private double capacidadM3;
    private boolean principal;

    public Tanque(String nombre, int posX, int posY, double capacidadM3, boolean principal) {
        this.nombre = nombre;
        this.posX = posX;
        this.posY = posY;
        this.capacidadM3 = capacidadM3;
        this.principal = principal;
    }

    public String getNombre() { return nombre; }
    public int getPosX() { return posX; }
    public int getPosY() { return posY; }
    public double getCapacidadM3() { return capacidadM3; }
    public boolean isPrincipal() { return principal; }
}
