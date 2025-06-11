package pe.pucp.plg.model.template;

/**
 * Representa los atributos estáticos de un camión (plantilla fija).
 */
public class CamionTemplate {

    private String id;
    private double capacidadCarga;
    private double tara;
    private double capacidadCombustible;

    private static final double pesoTara = 2.5;
    private static final double pesoCargoPorM3 = 0.5;

    public CamionTemplate(String id, double capacidadCarga, double tara, double capacidadCombustible) {
        this.id = id;
        this.capacidadCarga = capacidadCarga;
        this.tara = tara;
        this.capacidadCombustible = capacidadCombustible;
    }

    public CamionTemplate() {

    }

    // --- Getters y Setters ---
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public double getCapacidadCarga() {
        return capacidadCarga;
    }

    public void setCapacidadCarga(double capacidadCarga) {
        this.capacidadCarga = capacidadCarga;
    }

    public double getTara() {
        return tara;
    }

    public void setTara(double tara) {
        this.tara = tara;
    }

    public double getCapacidadCombustible() {
        return capacidadCombustible;
    }

    public void setCapacidadCombustible(double capacidadCombustible) {
        this.capacidadCombustible = capacidadCombustible;
    }

    public static double getPesoTara() {
        return pesoTara;
    }

    public static double getPesoCargoPorM3() {
        return pesoCargoPorM3;
    }
}
