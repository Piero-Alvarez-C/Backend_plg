package pe.pucp.plg.model;

public class Averia {
    private String turno;           // T1, T2, T3
    private String codigoVehiculo; // Ej: TA01
    private String tipoIncidente;  // TI1, TI2, TI3

    public Averia() {}

    public Averia(String turno, String codigoVehiculo, String tipoIncidente) {
        this.turno = turno;
        this.codigoVehiculo = codigoVehiculo;
        this.tipoIncidente = tipoIncidente;
    }

    public String getTurno() {
        return turno;
    }

    public void setTurno(String turno) {
        this.turno = turno;
    }

    public String getCodigoVehiculo() {
        return codigoVehiculo;
    }

    public void setCodigoVehiculo(String codigoVehiculo) {
        this.codigoVehiculo = codigoVehiculo;
    }

    public String getTipoIncidente() {
        return tipoIncidente;
    }

    public void setTipoIncidente(String tipoIncidente) {
        this.tipoIncidente = tipoIncidente;
    }
}