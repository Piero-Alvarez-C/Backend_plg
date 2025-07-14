package pe.pucp.plg.model.template;

/**
 * Representa los atributos estáticos de un camión (plantilla fija).
 */
public class CamionTemplate {

    private final String id;
    private final double capacidadCarga;
    private final double tara;
    private final double capacidadCombustible;
    private final int initialX;
    private final int initialY;
    private final double consumoCombustiblePorKm; // Added: e.g., liters per kilometer
    private final double velocidadPromedioKmPorMin; // Added: e.g., km per minute

    private static final double pesoTara = 2.5;
    private static final double pesoCargoPorM3 = 0.5;

    // Constructor with initial position, consumption, and speed
    public CamionTemplate(String id, double capacidadCarga, double tara, double capacidadCombustible, 
                          int initialX, int initialY, 
                          double consumoCombustiblePorKm, double velocidadPromedioKmPorMin) {
        this.id = id;
        this.capacidadCarga = capacidadCarga;
        this.tara = tara;
        this.capacidadCombustible = capacidadCombustible;
        this.initialX = initialX;
        this.initialY = initialY;
        this.consumoCombustiblePorKm = consumoCombustiblePorKm;
        this.velocidadPromedioKmPorMin = velocidadPromedioKmPorMin;
    }

    // Overloaded constructor with default initial position (0,0) and example consumption/speed
    public CamionTemplate(String id, double capacidadCarga, double tara, double capacidadCombustible) {
        this(id, capacidadCarga, tara, capacidadCombustible, 12, 8, 0.2, 0.833); // Default 0.2 L/km, 50 km/h (0.833 km/min)
    }
    
    // Overloaded constructor with specified initial position and example consumption/speed
    public CamionTemplate(String id, double capacidadCarga, double tara, double capacidadCombustible, int initialX, int initialY) {
        this(id, capacidadCarga, tara, capacidadCombustible, initialX, initialY, 0.2, 0.833); // Default 0.2 L/km, 50 km/h (0.833 km/min)
    }


    // --- Getters y Setters ---
    public String getId() {
        return id;
    }

    public double getCapacidadCarga() {
        return capacidadCarga;
    }

    public double getTara() {
        return tara;
    }

    public double getCapacidadCombustible() {
        return capacidadCombustible;
    }

    public int getInitialX() { // Added
        return initialX;
    }

    public int getInitialY() { // Added
        return initialY;
    }

    public double getConsumoCombustiblePorKm() { // Added
        return consumoCombustiblePorKm;
    }

    public double getVelocidadPromedioKmPorMin() { // Added
        return velocidadPromedioKmPorMin;
    }

    public static double getPesoTara() {
        return pesoTara;
    }

    public static double getPesoCargoPorM3() {
        return pesoCargoPorM3;
    }
}
