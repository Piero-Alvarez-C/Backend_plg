package pe.pucp.plg.dto;

public class PedidoDTO {
    private String idCliente;
    private int posX;
    private int posY;
    private int volumenM3;
    private int horasLimite;

    public PedidoDTO(String idCliente, int posX, int posY, int volumenM3, int horasLimite) {
        this.idCliente = idCliente;
        this.posX = posX;
        this.posY = posY;
        this.volumenM3 = volumenM3;
        this.horasLimite = horasLimite;
    }

    // Getters
    public String getIdCliente() { return idCliente; }
    public int getPosX() { return posX; }
    public int getPosY() { return posY; }
    public int getVolumenM3() { return volumenM3; }
    public int getHorasLimite() { return horasLimite; }
}
