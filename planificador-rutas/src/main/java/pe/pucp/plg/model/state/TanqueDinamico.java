package pe.pucp.plg.model.state;

import pe.pucp.plg.model.template.TanqueTemplate;

public class TanqueDinamico {
    private final TanqueTemplate plantilla;
    private double capacidadDisponible;

    public TanqueDinamico(TanqueTemplate plantilla) {
        this.plantilla = plantilla;
        this.capacidadDisponible    = this.plantilla.getCapacidadTotal();
    }

    // Copy constructor
    public TanqueDinamico(TanqueDinamico original) {
        this.plantilla = original.plantilla; // TanqueTemplate is immutable
        this.capacidadDisponible = original.capacidadDisponible;
    }

    public String getId() { return plantilla.getId(); } 
    public int getPosX() { return plantilla.getPosX(); }
    public int getPosY() { return plantilla.getPosY(); }
    public double getCapacidadTotal() { return plantilla.getCapacidadTotal(); }
    public double getDisponible()    { return capacidadDisponible; }
    public void setDisponible(double d) { this.capacidadDisponible = d; }

    // Method to dispense fuel
    public void dispensarCombustible(double cantidad) {
        if (cantidad <= this.capacidadDisponible) {
            this.capacidadDisponible -= cantidad;
        } else {
            // Handle error: not enough fuel to dispense
            System.err.println("Error: Tanque " + getId() + " no tiene suficiente combustible para dispensar " + cantidad);
            // Optionally throw an exception
        }
    }

    // Method to refill the tank (e.g., daily)
    public void rellenarAlMaximo() {
        this.capacidadDisponible = this.plantilla.getCapacidadTotal();
    }
}
