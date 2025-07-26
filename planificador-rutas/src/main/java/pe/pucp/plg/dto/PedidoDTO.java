package pe.pucp.plg.dto;

import java.time.LocalDateTime;

public class PedidoDTO {

    private int id;
    private String idCliente; // ← agrega esto
    private int x;
    private int y;
    private LocalDateTime tiempoCreacion;
    private LocalDateTime tiempoLimite;
    private double volumen;
    private boolean atendido;
    private boolean descartado;
    private boolean programado;
    private boolean enEntrega;

    // Getters y Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getIdCliente() { return idCliente; }
    public void setIdCliente(String idCliente) { this.idCliente = idCliente; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public LocalDateTime getTiempoCreacion() { return tiempoCreacion; }
    public void setTiempoCreacion(LocalDateTime tiempoCreacion) { this.tiempoCreacion = tiempoCreacion; }

    public LocalDateTime getTiempoLimite() { return tiempoLimite; }
    public void setTiempoLimite(LocalDateTime tiempoLimite) { this.tiempoLimite = tiempoLimite; }

    public double getVolumen() { return volumen; }
    public void setVolumen(double volumen) { this.volumen = volumen; }

    public boolean isAtendido() { return atendido; }
    public void setAtendido(boolean atendido) { this.atendido = atendido; }

    public boolean isDescartado() { return descartado; }
    public void setDescartado(boolean descartado) { this.descartado = descartado; }

    public boolean isProgramado() { return programado; }
    public void setProgramado(boolean programado) { this.programado = programado; }

    public boolean isEnEntrega() { return enEntrega; }
    public void setEnEntrega(boolean enEntrega) { this.enEntrega = enEntrega; }

}
