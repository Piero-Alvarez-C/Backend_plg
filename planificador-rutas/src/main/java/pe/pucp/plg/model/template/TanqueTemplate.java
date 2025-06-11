package pe.pucp.plg.model.template;

public class TanqueTemplate {
    private final int posX;
    private final int posY;
    private final double capacidadTotal;

    public TanqueTemplate(int x, int y, double cap) {
        this.posX = x;
        this.posY = y;
        this.capacidadTotal = cap;
    }

    public int getPosX() { return posX; }
    public int getPosY() { return posY; }
    public double getCapacidadTotal() { return capacidadTotal; }
}
