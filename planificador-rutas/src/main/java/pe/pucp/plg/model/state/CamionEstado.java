package pe.pucp.plg.model.state;

import java.awt.Point;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.template.CamionTemplate;

public class CamionEstado {

    public enum TruckStatus {
        AVAILABLE,      // Disponible para nuevas asignaciones
        DELIVERING,     // En ruta para descargar o recargar
        RETURNING,      // Regresando al depósito o a un tanque
        UNAVAILABLE,      // Procesando un pedido
        BREAKDOWN,
        MAINTENANCE       
    }

    // === Referencia a la plantilla ===
    private final CamionTemplate plantilla;

    // --- Identificación y capacidades ---
    private double combustibleActual;
    private double capacidadDisponible;

    // --- Posición y timing ---
    private int x = 12;
    private int y = 8;
    private LocalDateTime tiempoLibre;
    /**
     * Tiempo hasta el que el camión permanecerá inactivo exclusivamente por una avería.
     * Se mantiene separado de tiempoLibre (recargas/servicios programados).
     */
    private LocalDateTime tiempoLibreAveria; 
    private LocalDateTime tiempoInicioAveria; // Nuevo: instante en que inicia la avería
    private boolean enRetorno = false; 
    private TruckStatus status = TruckStatus.AVAILABLE;

    // --- Rutas y trayectoria ---
    private List<Pedido> pedidosCargados = new ArrayList<>();   // Antes rutaPendiente
    private List<Point> rutaActual = new ArrayList<>();
    private int pasoActual = 0;      
    private List<Point> history = new ArrayList<>();
    
    // --- Desvios ---
    private List<Point> rutaBackup = new ArrayList<>();
    private List<Pedido> pedidosBackup = new ArrayList<>();
    private Pedido pedidoDesvio = null;

    // --- Estadísticas de consumo ---
    private double consumoAcumulado = 0.0;
    private double combustibleGastado = 0.0;

    public LocalDateTime getTiempoInicioAveria() {
        return tiempoInicioAveria;
    }
    public void setTiempoInicioAveria(LocalDateTime tiempoInicioAveria) {
        this.tiempoInicioAveria = tiempoInicioAveria;
    }

    // --- Para recarga en tanque ---
    private TanqueDinamico reabastecerEnTanque = null; 
    private LocalDateTime retHora;
    private int retStartX = 0, retStartY = 0, retDestX = 0, retDestY = 0;
    private TanqueDinamico tanqueOrigen = null;
    private TanqueDinamico tanqueOrigenBackup = null;
    
    // --- Para averías ---
    private String tipoAveriaActual = null; // T1, T2, T3 o null si no hay avería
    private boolean enTaller = false; // Indica si el camión está en taller tras una avería tipo T2 o T3

    public CamionEstado(CamionTemplate plantilla, int initialX, int initialY) {
        this.plantilla = Objects.requireNonNull(plantilla, "La plantilla del camión no puede ser nula.");
        this.x = initialX;
        this.y = initialY;
        this.combustibleActual = plantilla.getCapacidadCombustible();
        this.capacidadDisponible = plantilla.getCapacidadCarga();
        this.pasoActual = 0;
        this.reabastecerEnTanque = null;
        this.status = TruckStatus.AVAILABLE; 
        this.tiempoLibre = null;
        this.tiempoLibreAveria = null; 
    }

    public CamionEstado(CamionEstado original) {
        this.plantilla = original.plantilla; 
        this.x = original.x;
        this.y = original.y;
        this.combustibleActual = original.combustibleActual;
        this.capacidadDisponible = original.capacidadDisponible;
        this.pedidosCargados = new ArrayList<>();
        for (Pedido p : original.pedidosCargados) {
            this.pedidosCargados.add(new Pedido(p)); 
        }
        this.rutaActual = new ArrayList<>();
        for (Point p : original.rutaActual) {
            this.rutaActual.add(new Point(p.x, p.y)); 
        }
        this.pasoActual = original.pasoActual;
        this.reabastecerEnTanque = (original.reabastecerEnTanque != null) ? new TanqueDinamico(original.reabastecerEnTanque) : null;
        this.status = original.status;
        this.tiempoLibre = original.tiempoLibre;
        this.tiempoLibreAveria = original.tiempoLibreAveria;
        this.tipoAveriaActual = original.tipoAveriaActual;
    }
    
    // Getters
    public CamionTemplate getPlantilla() { return plantilla; }
    public int getX() { return x; }
    public int getY() { return y; }
    public double getCombustibleActual() { return combustibleActual; }
    public double getCapacidadDisponible() { return capacidadDisponible; }
    public List<Pedido> getPedidosCargados() { return pedidosCargados; }
    public List<Point> getRutaActual() { return rutaActual; }
    public int getPasoActual() { return pasoActual; }
    public TanqueDinamico getTanqueDestinoRecarga() { return reabastecerEnTanque; } // Alias for getTanqueDestinoRuta
    public TruckStatus getStatus() { return status; }
    public LocalDateTime getTiempoLibre() { return tiempoLibre; }
    public LocalDateTime getTiempoLibreAveria() { return tiempoLibreAveria; }
    public void setTiempoLibreAveria(LocalDateTime nuevo) { this.tiempoLibreAveria = nuevo; }
    public LocalDateTime getRetHora() { return retHora; }
    public int getRetStartX() { return retStartX; }
    public int getRetStartY() { return retStartY; }
    public int getRetDestX() { return retDestX; }
    public int getRetDestY() { return retDestY; }
    public List<Point> getHistory() { return history; }
    public boolean getEnRetorno() { return enRetorno; }
    public double getConsumoAcumulado() { return consumoAcumulado; }
    public double getCombustibleGastado() { return combustibleGastado; }
    public String getTipoAveriaActual() { return tipoAveriaActual; }
    public boolean isEnTaller() { return enTaller; }

    public List<Point> getRutaBackup() { return rutaBackup; }
    public List<Pedido> getPedidosBackup() { return pedidosBackup; }
    public Pedido getPedidoDesvio() { return pedidoDesvio; }
    public TanqueDinamico getTanqueOrigen() { return tanqueOrigen; }
    public TanqueDinamico getTanqueOrigenBackup() { return tanqueOrigenBackup; }

    // Setters for cloned instances used by ACOPlanner
    public void setCapacidadDisponible(double nuevaCapacidad) { this.capacidadDisponible = nuevaCapacidad; }
    public void setCombustibleDisponible(double nuevoCombustible) { this.combustibleActual = nuevoCombustible; }
    public void setTiempoLibre(LocalDateTime nuevoTiempoLibre) { this.tiempoLibre = nuevoTiempoLibre; }
    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }   
    public void setStatus(TruckStatus newStatus) { this.status = newStatus; }
    public void setRuta(List<Point> nuevaRuta) { this.rutaActual = new ArrayList<>(nuevaRuta); }
    public void setPasoActual(int nuevoPaso) { this.pasoActual = nuevoPaso; }
    public void setReabastecerEnTanque(TanqueDinamico tanque) { this.reabastecerEnTanque = tanque; }
    public void setRetHora(LocalDateTime hora) { this.retHora = hora; }
    public void setRetStartX(int x) { this.retStartX = x; }
    public void setRetStartY(int y) { this.retStartY = y; }
    public void setRetDestX(int x) { this.retDestX = x; }
    public void setRetDestY(int y) { this.retDestY = y; }
    public void setEnRetorno(boolean enRet) { this.enRetorno = enRet; }
    public void setConsumoAcumulado(double consumo) { this.consumoAcumulado = consumo; }
    public void setCombustibleGastado(double combustible) { this.combustibleGastado = combustible; }
    public void setTipoAveriaActual(String tipo) { this.tipoAveriaActual = tipo; }
    public void setEnTaller(boolean enTaller) { this.enTaller = enTaller; }

    public void setRutaBackup(List<Point> r) { this.rutaBackup = r; }
    public void setPedidosBackup(List<Pedido> p) { this.pedidosBackup = p; }
    public void setPedidoDesvio(Pedido d) { this.pedidoDesvio = d; }
    public void setTanqueOrigen(TanqueDinamico t) { this.tanqueOrigen = t; }
    public void setTanqueOrigenBackup(TanqueDinamico t) { this.tanqueOrigenBackup = t; }

    public void clearDesvio() {
        this.rutaBackup = null;
        this.pedidosBackup = null;
        this.pedidoDesvio = null;
    }

    // --- Checkers ---
    public boolean tienePasosPendientes() {
        return !rutaActual.isEmpty();
    }

    public void registrarCargaPedido(Pedido p) { 
        if (getCapacidadDisponible() >= p.getVolumen()) {
            this.capacidadDisponible -= p.getVolumen();
        } 
    }    

    public boolean tieneRutaAsignada() {
        return !rutaActual.isEmpty();
    }
    
    public boolean estaEnDestinoDeRuta() {
        if (!tieneRutaAsignada()) return true;
        return rutaActual.isEmpty();
    }

    public boolean estaLibre(LocalDateTime tiempoActual) {
        return (this.status == TruckStatus.AVAILABLE)
           && (this.tiempoLibre == null || !this.tiempoLibre.isAfter(tiempoActual))
           && (this.tiempoLibreAveria == null || !this.tiempoLibreAveria.isAfter(tiempoActual));
    }

    public void recargarCombustible() { 
        combustibleActual = plantilla.getCapacidadCombustible();
    }

    public void avanzarUnPaso() {
        if (!tienePasosPendientes()) return;

        pasoActual++; // ¡Incrementar el contador de pasos!

        double pesoTotal = CamionTemplate.getPesoTara() + (getCapacidadDisponible() * CamionTemplate.getPesoCargoPorM3());
    double gasto = pesoTotal / 180.0;
    consumoAcumulado += gasto;
    combustibleActual -= gasto ;
    combustibleGastado += gasto;
    Point next = rutaActual.remove(0);
    moverA(next);
}

    public void moverA(Point p) {
        x = p.x;
        y = p.y;
        //history.add(new Point(p.x, p.y));
    }

    public void reset() {
        x = 12;
        y = 8;
        combustibleActual = plantilla.getCapacidadCombustible();
        capacidadDisponible = plantilla.getCapacidadCarga();
        consumoAcumulado = 0.0;
        combustibleGastado = 0.0;
        pedidosCargados.clear();
        rutaActual = Collections.emptyList();
        history.clear();
        reabastecerEnTanque = null;
        status = TruckStatus.AVAILABLE;
        tiempoLibre = null;
        tiempoLibreAveria = null;
        enRetorno = false;
        enTaller = false; // Reiniciar estado de taller
    }

    public CamionEstado deepClone() {
        return new CamionEstado(this);
    }

}
