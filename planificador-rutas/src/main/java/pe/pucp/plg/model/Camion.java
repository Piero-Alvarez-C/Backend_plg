package pe.pucp.plg.model;

public class Camion {
    private String codigo;
    private String tipo;
    private double capacidadM3;
    private double taraTon;
    private double pesoCargaTon;
    private double pesoTotalTon;

    public Camion(String codigo, String tipo, double capacidadM3, double taraTon, double pesoCargaTon) {
        this.codigo = codigo;
        this.tipo = tipo;
        this.capacidadM3 = capacidadM3;
        this.taraTon = taraTon;
        this.pesoCargaTon = pesoCargaTon;
        this.pesoTotalTon = taraTon + pesoCargaTon;
    }

    // Getters y Setters
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public double getCapacidadM3() { return capacidadM3; }
    public void setCapacidadM3(double capacidadM3) { this.capacidadM3 = capacidadM3; }

    public double getTaraTon() { return taraTon; }
    public void setTaraTon(double taraTon) { this.taraTon = taraTon; }

    public double getPesoCargaTon() { return pesoCargaTon; }
    public void setPesoCargaTon(double pesoCargaTon) { this.pesoCargaTon = pesoCargaTon; }

    public double getPesoTotalTon() { return pesoTotalTon; }
    public void setPesoTotalTon(double pesoTotalTon) { this.pesoTotalTon = pesoTotalTon; }
}
