package pe.pucp.plg.model.common;

import java.time.LocalDate;

public class Mantenimiento {
    private LocalDate fecha;
    private String codigoVehiculo;

    public Mantenimiento() {}

    public Mantenimiento(LocalDate fecha, String codigoVehiculo) {
        this.fecha = fecha;
        this.codigoVehiculo = codigoVehiculo;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
    }

    public String getCodigoVehiculo() {
        return codigoVehiculo;
    }

    public void setCodigoVehiculo(String codigoVehiculo) {
        this.codigoVehiculo = codigoVehiculo;
    }
}
