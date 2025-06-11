package pe.pucp.plg.model.state;

import pe.pucp.plg.model.template.TanqueTemplate;

public class TanqueDinamico {
    private final TanqueTemplate plantilla;
    private double capacidadDisponible;

    public TanqueDinamico(TanqueTemplate plantilla) {
        this.plantilla = plantilla;
        this.capacidadDisponible    = this.plantilla.getCapacidadTotal();
    }

    public int getPosX() { return plantilla.getPosX(); }
    public int getPosY() { return plantilla.getPosY(); }
    public double getCapacidadTotal() { return plantilla.getCapacidadTotal(); }
    public double getDisponible()    { return capacidadDisponible; }
    public void setDisponible(double d) { this.capacidadDisponible = d; }
}
