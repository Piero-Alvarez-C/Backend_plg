package pe.pucp.plg.dto;

import java.util.ArrayList;
import java.util.List;

public class TanqueDTO {
    private String id;
    private int posX;
    private int posY;
    private double capacidadTotal;
    private double capacidadDisponible;
    private List<String> pedidos = new ArrayList<>();

    // Getters y setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getPosX() { return posX; }
    public void setPosX(int posX) { this.posX = posX; }

    public int getPosY() { return posY; }
    public void setPosY(int posY) { this.posY = posY; }

    public double getCapacidadTotal() { return capacidadTotal; }
    public void setCapacidadTotal(double capacidadTotal) { this.capacidadTotal = capacidadTotal; }

    public double getCapacidadDisponible() { return capacidadDisponible; }
    public void setCapacidadDisponible(double capacidadDisponible) { this.capacidadDisponible = capacidadDisponible; }

    public List<String> getPedidos() { return pedidos; }
    public void setPedidos(List<String> pedidos) { this.pedidos = pedidos; }
}
