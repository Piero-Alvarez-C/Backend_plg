package pe.pucp.plg.dto;

public class SimulationStatusDTO {
    private String simulationId;
    private String nombreSimulacion;
    private String estado; // Ej: EN_PROGRESO, FINALIZADA, CANCELADA
    private int avance;    // Porcentaje de avance (0 a 100)

    public String getSimulationId() {
        return simulationId;
    }

    public void setSimulationId(String simulationId) {
        this.simulationId = simulationId;
    }

    public String getNombreSimulacion() {
        return nombreSimulacion;
    }

    public void setNombreSimulacion(String nombreSimulacion) {
        this.nombreSimulacion = nombreSimulacion;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public int getAvance() {
        return avance;
    }

    public void setAvance(int avance) {
        this.avance = avance;
    }
}
