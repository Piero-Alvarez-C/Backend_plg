package pe.pucp.plg.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class PedidoDTO {

    private int id;
    private String idCliente; // ← agrega esto
    private int x;
    private int y;
    private int tiempoCreacion;
    private int tiempoLimite;
    private double volumen;
    private boolean atendido;
    private boolean descartado;

    // Getters y Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getIdCliente() { return idCliente; }
    public void setIdCliente(String idCliente) { this.idCliente = idCliente; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public int getTiempoCreacion() { return tiempoCreacion; }
    public void setTiempoCreacion(int tiempoCreacion) { this.tiempoCreacion = tiempoCreacion; }

    public int getTiempoLimite() { return tiempoLimite; }
    public void setTiempoLimite(int tiempoLimite) { this.tiempoLimite = tiempoLimite; }

    public double getVolumen() { return volumen; }
    public void setVolumen(double volumen) { this.volumen = volumen; }

    public boolean isAtendido() { return atendido; }
    public void setAtendido(boolean atendido) { this.atendido = atendido; }

    public boolean isDescartado() { return descartado; }
    public void setDescartado(boolean descartado) { this.descartado = descartado; }

}
