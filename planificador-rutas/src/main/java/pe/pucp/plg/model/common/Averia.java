package pe.pucp.plg.model.common;

public class Averia {
    private String turno;           // T1, T2, T3
    private String codigoVehiculo; // Ej: TA01
    private String tipoIncidente;  // TI1, TI2, TI3
    private boolean fromFile;      // Indica si fue cargada desde archivo

    public Averia() {
        this.fromFile = false; // Por defecto, no es de archivo
    }

    public Averia(String turno, String codigoVehiculo, String tipoIncidente) {
        this.turno = turno;
        this.codigoVehiculo = codigoVehiculo;
        this.tipoIncidente = tipoIncidente;
        this.fromFile = false; // Por defecto, no es de archivo
    }
    
    public Averia(String turno, String codigoVehiculo, String tipoIncidente, boolean fromFile) {
        this.turno = turno;
        this.codigoVehiculo = codigoVehiculo;
        this.tipoIncidente = tipoIncidente;
        this.fromFile = fromFile;
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
    
    public boolean isFromFile() {
        return fromFile;
    }
    
    public void setFromFile(boolean fromFile) {
        this.fromFile = fromFile;
    }
}