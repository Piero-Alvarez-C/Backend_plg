package pe.pucp.plg.dto;

public class SimulationRequest {
    private String nombreSimulacion;
    private String fechaInicio; // Formato "YYYY-MM-DD"
    private int duracionDias;   // Ej. 7 para simulación semanal
    private boolean esColapso;  // Indica si es una simulación de colapso

    public String getNombreSimulacion() {
        return nombreSimulacion;
    }

    public void setNombreSimulacion(String nombreSimulacion) {
        this.nombreSimulacion = nombreSimulacion;
    }

    public String getFechaInicio() {
        return fechaInicio;
    }

    public void setFechaInicio(String fechaInicio) {
        this.fechaInicio = fechaInicio;
    }

    public int getDuracionDias() {
        return duracionDias;
    }

    public void setDuracionDias(int duracionDias) {
        this.duracionDias = duracionDias;
    }

    public boolean isEsColapso() {
        return esColapso;
    }

    public void setEsColapso(boolean esColapso) {
        this.esColapso = esColapso;
    }
}
