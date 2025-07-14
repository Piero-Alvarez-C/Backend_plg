package pe.pucp.plg.model.common;

import java.time.LocalDateTime;

public class Pedido {
    int id, x, y;
    LocalDateTime tiempoCreacion, tiempoLimite;
    double volumen;
    boolean atendido = false;
    boolean descartado = false;
    boolean programado = false; 
    boolean enEntrega = false;
    private LocalDateTime horaEntregaProgramada;

    public Pedido(int id, LocalDateTime tiempoCreacion, int x, int y, double volumen, LocalDateTime tiempoLimite) {
        this.id = id; this.tiempoCreacion = tiempoCreacion;
        this.x = x; this.y = y; this.volumen = volumen;
        this.tiempoLimite = tiempoLimite;
    }

    // Copy constructor
    public Pedido(Pedido original) {
        this.id = original.id;
        this.x = original.x;
        this.y = original.y;
        this.tiempoCreacion = original.tiempoCreacion;
        this.tiempoLimite = original.tiempoLimite;
        this.volumen = original.volumen;
        this.atendido = original.atendido;
        this.descartado = original.descartado;
        this.programado = original.programado;
    }
    // → getters para la tabla de pedidos:
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getX() { return x; }
    public int getY() { return y; }
    public double getVolumen() { return volumen; }
    public LocalDateTime getTiempoLimite() { return tiempoLimite; }
    public boolean isAtendido() { return atendido; }
    public boolean isDescartado() {
        return descartado;
    }
    public boolean isProgramado() {return programado; }
    public LocalDateTime getTiempoCreacion() { return tiempoCreacion; }
    public void setTiempoCreacion(LocalDateTime tc) {this.tiempoCreacion = tc; }
    public void setDescartado(boolean descartado) {
        this.descartado = descartado;
    }
    public void setTiempoLimite(LocalDateTime tiempoLimite) {
        this.tiempoLimite = tiempoLimite;
    }
    // → marcar como atendido:
    public void setProgramado(boolean programado) {this.programado = programado; }
    public void setAtendido(boolean a) { this.atendido = a; }

    public LocalDateTime getHoraEntregaProgramada() {
        return horaEntregaProgramada;
    }

    public void setHoraEntregaProgramada(LocalDateTime horaEntregaProgramada) {
        this.horaEntregaProgramada = horaEntregaProgramada;
    }

    public boolean isEnEntrega() {
        return enEntrega;
    }

    public void setEnEntrega(boolean enEntrega) {
        this.enEntrega = enEntrega;
    }
}
