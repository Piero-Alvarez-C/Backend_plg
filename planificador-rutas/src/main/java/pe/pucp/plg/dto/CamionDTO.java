package pe.pucp.plg.dto;

public class CamionDTO {
    private String codigo;
    private String tipo;
    private double capacidadM3;
    private double pesoTotalTon;

    public CamionDTO(String codigo, String tipo, double capacidadM3, double pesoTotalTon) {
        this.codigo = codigo;
        this.tipo = tipo;
        this.capacidadM3 = capacidadM3;
        this.pesoTotalTon = pesoTotalTon;
    }

    // Getters y Setters
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public double getCapacidadM3() { return capacidadM3; }
    public void setCapacidadM3(double capacidadM3) { this.capacidadM3 = capacidadM3; }

    public double getPesoTotalTon() { return pesoTotalTon; }
    public void setPesoTotalTon(double pesoTotalTon) { this.pesoTotalTon = pesoTotalTon; }
}
