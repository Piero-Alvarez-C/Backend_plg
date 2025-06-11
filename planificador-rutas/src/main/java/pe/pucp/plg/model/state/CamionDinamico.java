package pe.pucp.plg.model.state;

import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.template.CamionTemplate;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CamionDinamico {
    public enum TruckStatus {
        AVAILABLE, DELIVERING, RETURNING
    }

    // === Referencia a la plantilla ===
    private final CamionTemplate plantilla;

    // --- Identificación y capacidades ---
    private double disponible;
    private static final double pesoTara = 2.5;
    private static final double pesoCargoPorM3 = 0.5;

    // --- Combustible ---
    private double combustibleDisponible;

    // --- Posición y timing ---
    private int x = 12, y = 8;
    private int libreEn = 0;
    private boolean enRetorno = false;
    private TruckStatus status = TruckStatus.AVAILABLE;

    // --- Rutas y trayectoria ---
    private final List<Pedido> rutaPendiente = new ArrayList<>();
    private List<Point> rutaActual = Collections.emptyList();
    private int pasoActual = 0;
    private final List<Point> history = new ArrayList<>();

    // --- Estadísticas de consumo ---
    private double consumoAcumulado = 0.0;
    private double combustibleGastado = 0.0;

    // --- Para recarga en tanque ---
    private TanqueDinamico reabastecerEnTanque = null;
    private int retHora = 0, retStartX = 0, retStartY = 0, retDestX = 0, retDestY = 0;

    // --- Constructor ---
    public CamionDinamico(String id, double capacidadCarga, double tara, double capacidadCombustible) {
        this.plantilla = new CamionTemplate();
        this.plantilla.setId(id);
        this.plantilla.setCapacidadCarga(capacidadCarga);
        this.plantilla.setTara(tara);
        this.plantilla.setCapacidadCombustible(capacidadCombustible);
        this.combustibleDisponible = capacidadCombustible;
    }

    // --- Checkers ---
    public boolean tienePasosPendientes() {
        return pasoActual < rutaActual.size();
    }

    // --- Getters y Setters ---
    public String getId() { return plantilla.getId(); }
    public double getCapacidad() { return plantilla.getCapacidadCarga(); }
    public double getDisponible() { return disponible; }
    public void setDisponible(double d) { this.disponible = d; }
    public double getTara() { return plantilla.getTara(); }
    public int getX() { return x; }
    public int getY() { return y; }
    public void setX(int x_aux) { this.x = x_aux; }
    public void setY(int y_aux) { this.y = y_aux; }
    public int getLibreEn() { return libreEn; }
    public void setLibreEn(int t) { this.libreEn = t; }
    public boolean isEnRetorno() { return enRetorno; }
    public void setEnRetorno(boolean enRet) { this.enRetorno = enRet; }
    public TruckStatus getStatus() { return status; }
    public void setStatus(TruckStatus s) { this.status = s; }

    public List<Pedido> getRutaPendiente() { return rutaPendiente; }
    public List<Point> getRutaActual() { return rutaActual; }
    public void setRutaActual(List<Point> ruta) { this.rutaActual = ruta; }
    public int getPasoActual() { return pasoActual; }
    public void setPasoActual(int pasoActual) { this.pasoActual = pasoActual; }

    public List<Point> getHistory() { return history; }

    public double getConsumoAcumulado() { return consumoAcumulado; }
    public void setConsumoAcumulado(double c) { this.consumoAcumulado = c; }

    public double getCombustibleGastado() { return combustibleGastado; }
    public void setCombustibleGastado(double c) { this.combustibleGastado = c; }

    public double getCapacidadCombustible() { return plantilla.getCapacidadCombustible(); }
    public double getCombustibleDisponible() { return combustibleDisponible; }
    public void setCombustibleDisponible(double c) { this.combustibleDisponible = c; }

    public static double getPesoTara() { return pesoTara; }
    public static double getPesoCargoPorM3() { return pesoCargoPorM3; }

    public TanqueDinamico getReabastecerEnTanque() { return reabastecerEnTanque; }
    public void setReabastecerEnTanque(TanqueDinamico t) { this.reabastecerEnTanque = t; }

    public int getRetHora() { return retHora; }
    public void setRetHora(int retHora) { this.retHora = retHora; }
    public int getRetStartX() { return retStartX; }
    public void setRetStartX(int retStartX) { this.retStartX = retStartX; }
    public int getRetStartY() { return retStartY; }
    public void setRetStartY(int retStartY) { this.retStartY = retStartY; }
    public int getRetDestX() { return retDestX; }
    public void setRetDestX(int retDestX) { this.retDestX = retDestX; }
    public int getRetDestY() { return retDestY; }
    public void setRetDestY(int retDestY) { this.retDestY = retDestY; }
}
