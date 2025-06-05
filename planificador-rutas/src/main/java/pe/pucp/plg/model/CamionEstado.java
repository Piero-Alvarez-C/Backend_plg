package pe.pucp.plg.model;

public class CamionEstado {
    public String id;
    public int posX, posY;
    public double capacidadDisponible;
    public int tiempoLibre;
    public double tara;                 // en kg (o unidades que uses)
    public double combustibleDisponible; // en galones

    public CamionEstado() {}
}
