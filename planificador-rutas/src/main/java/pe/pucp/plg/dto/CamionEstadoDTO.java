package pe.pucp.plg.dto;

import java.time.LocalDateTime;

public class CamionEstadoDTO {
    private String id;
    private int posX;
    private int posY;
    private double capacidadDisponible;
    private LocalDateTime tiempoLibre;
    private double tara;
    private double combustibleDisponible;

    public CamionEstadoDTO() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getPosX() { return posX; }
    public void setPosX(int posX) { this.posX = posX; }

    public int getPosY() { return posY; }
    public void setPosY(int posY) { this.posY = posY; }

    public double getCapacidadDisponible() { return capacidadDisponible; }
    public void setCapacidadDisponible(double capacidadDisponible) {
        this.capacidadDisponible = capacidadDisponible;
    }

    public LocalDateTime getTiempoLibre() { return tiempoLibre; }
    public void setTiempoLibre(LocalDateTime tiempoLibre) { this.tiempoLibre = tiempoLibre; }

    public double getTara() { return tara; }
    public void setTara(double tara) { this.tara = tara; }

    public double getCombustibleDisponible() { return combustibleDisponible; }
    public void setCombustibleDisponible(double combustibleDisponible) {
        this.combustibleDisponible = combustibleDisponible;
    }
}
