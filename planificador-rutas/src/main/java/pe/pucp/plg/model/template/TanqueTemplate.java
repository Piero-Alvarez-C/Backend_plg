package pe.pucp.plg.model.template;

public class TanqueTemplate {
    private final int posX;
    private final int posY;
    private final double capacidadTotal;
    private final String id; // Added id field

    public TanqueTemplate(String id, int x, int y, double cap) { // Added id to constructor
        this.id = id;
        this.posX = x;
        this.posY = y;
        this.capacidadTotal = cap;
    }

    public String getId() { return id; } // Added getter for id
    public int getPosX() { return posX; }
    public int getPosY() { return posY; }
    public double getCapacidadTotal() { return capacidadTotal; }
}
