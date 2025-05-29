package pe.pucp.plg.model;

import java.time.LocalDateTime;

public class Pedido {
    private LocalDateTime fechaHoraCreacion;
    private int posX;
    private int posY;
    private String idCliente;
    private int volumen;       // en m3
    private int plazoHoras;    // plazo máximo

    public Pedido() {}

    public Pedido(LocalDateTime fechaHoraCreacion, int posX, int posY, String idCliente, int volumen, int plazoHoras) {
        this.fechaHoraCreacion = fechaHoraCreacion;
        this.posX = posX;
        this.posY = posY;
        this.idCliente = idCliente;
        this.volumen = volumen;
        this.plazoHoras = plazoHoras;
    }

    public LocalDateTime getFechaHoraCreacion() {
        return fechaHoraCreacion;
    }

    public void setFechaHoraCreacion(LocalDateTime fechaHoraCreacion) {
        this.fechaHoraCreacion = fechaHoraCreacion;
    }

    public int getPosX() {
        return posX;
    }

    public void setPosX(int posX) {
        this.posX = posX;
    }

    public int getPosY() {
        return posY;
    }

    public void setPosY(int posY) {
        this.posY = posY;
    }

    public String getIdCliente() {
        return idCliente;
    }

    public void setIdCliente(String idCliente) {
        this.idCliente = idCliente;
    }

    public int getVolumen() {
        return volumen;
    }

    public void setVolumen(int volumen) {
        this.volumen = volumen;
    }

    public int getPlazoHoras() {
        return plazoHoras;
    }

    public void setPlazoHoras(int plazoHoras) {
        this.plazoHoras = plazoHoras;
    }
}
