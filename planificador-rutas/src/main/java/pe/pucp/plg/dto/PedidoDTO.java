package pe.pucp.plg.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class PedidoDTO {

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd/MM/yyyy HH:mm")
    private LocalDateTime fechaHoraCreacion;

    private String idCliente;
    private int posX;
    private int posY;
    private int volumenM3;
    private int horasLimite;

    public PedidoDTO(LocalDateTime fechaHoraCreacion, String idCliente, int posX, int posY, int volumenM3, int horasLimite) {
        this.fechaHoraCreacion = fechaHoraCreacion;
        this.idCliente = idCliente;
        this.posX = posX;
        this.posY = posY;
        this.volumenM3 = volumenM3;
        this.horasLimite = horasLimite;
    }

    // Getters
    public LocalDateTime getFechaHoraCreacion() { return fechaHoraCreacion; }
    public String getIdCliente() { return idCliente; }
    public int getPosX() { return posX; }
    public int getPosY() { return posY; }
    public int getVolumenM3() { return volumenM3; }
    public int getHorasLimite() { return horasLimite; }
}
