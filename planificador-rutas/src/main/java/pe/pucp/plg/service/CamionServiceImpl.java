package pe.pucp.plg.service;

import org.springframework.stereotype.Service;
import pe.pucp.plg.model.Camion;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class CamionServiceImpl implements CamionService {

    /**
     * Vuelve el camión a su estado inicial (lleno y en depósito).
     */
    @Override
    public void reset(Camion camion) {
        camion.setDisponible(camion.getCapacidad());
        camion.setCombustibleDisponible(camion.getCapacidadCombustible());
        camion.setConsumoAcumulado(0);
        camion.setCombustibleGastado(0);
        camion.setX(12);
        camion.setY(8);
        camion.setLibreEn(0);
        camion.setEnRetorno(false);
        camion.setStatus(Camion.TruckStatus.AVAILABLE);
        camion.getRutaPendiente().clear();
        camion.setRutaActual(Collections.emptyList());
        camion.setPasoActual(0);
        camion.getHistory().clear();
        camion.setReabastecerEnTanque(null);
    }

    /**
     * Recarga el combustible al máximo.
     */
    @Override
    public void recargarCombustible(Camion camion) {
        camion.setCombustibleDisponible(camion.getCapacidadCombustible());
    }


    /**
     * Avanza un solo paso en la ruta actual, consumiendo combustible.
     */
    @Override
    public void avanzarUnPaso(Camion camion) {
        if (!camion.tienePasosPendientes()) return;
        double pesoTotal = camion.getPesoTara() + (camion.getDisponible() * camion.getPesoCargoPorM3());
        double gasto = pesoTotal / 180.0;
        camion.setConsumoAcumulado(camion.getConsumoAcumulado() + gasto);
        camion.setCombustibleDisponible(camion.getCombustibleDisponible() - gasto);
        camion.setCombustibleGastado(camion.getCombustibleGastado() + gasto);
        Point next = camion.getRutaActual().get(camion.getPasoActual());
        camion.setPasoActual(camion.getPasoActual() + 1);
        moverA(camion, next);
    }

    /**
     * Mueve al camión a la posición p y registra en historial.
     */
    @Override
    public void moverA(Camion camion, Point p) {
        camion.setX(p.x);
        camion.setY(p.y);
        camion.getHistory().add(new Point(p.x, p.y));
    }

    /**
     * Define la ruta Manhattan de pasos a seguir.
     */
    @Override
    public void setRuta(Camion camion, List<Point> ruta) {
        camion.setRutaActual(new ArrayList<>(ruta));
        camion.setPasoActual(0);
    }

    /**
     * Añade un camino al historial (sin resetear). Ideal al comienzo.
     */
    @Override
    public void appendToHistory(Camion camion, List<Point> path) {
        if (camion.getHistory().isEmpty())
            camion.getHistory().add(new Point(camion.getX(), camion.getY()));
        if (path != null)
            camion.getHistory().addAll(path);
    }
}
