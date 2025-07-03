package pe.pucp.plg.service.Impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.model.state.CamionDinamico;
import pe.pucp.plg.model.template.CamionTemplate;
import pe.pucp.plg.repository.CamionTemplateRepository;
import pe.pucp.plg.service.CamionService;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class CamionServiceImpl implements CamionService {

    private final CamionTemplateRepository camionTemplateRepository;

    @Autowired
    public CamionServiceImpl(CamionTemplateRepository camionTemplateRepository) {
        this.camionTemplateRepository = camionTemplateRepository;
    }
    /**
     * Vuelve el camión a su estado inicial (lleno y en depósito).
     */
    @Override
    public void reset(CamionDinamico camion) {
        camion.setDisponible(camion.getCapacidad());
        camion.setCombustibleDisponible(camion.getCapacidadCombustible());
        camion.setConsumoAcumulado(0);
        camion.setCombustibleGastado(0);
        camion.setX(12);
        camion.setY(8);
        camion.setLibreEn(0);
        camion.setEnRetorno(false);
        camion.setStatus(CamionDinamico.TruckStatus.AVAILABLE);
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
    public void recargarCombustible(CamionDinamico camion) {
        camion.setCombustibleDisponible(camion.getCapacidadCombustible());
    }


    /**
     * Avanza un solo paso en la ruta actual, consumiendo combustible.
     */
    @Override
    public void avanzarUnPaso(CamionDinamico camion) {
        if (!camion.tienePasosPendientes()) return;
        double pesoTotal = CamionDinamico.getPesoTara() + (camion.getDisponible() * CamionDinamico.getPesoCargoPorM3());
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
    public void moverA(CamionDinamico camion, Point p) {
        camion.setX(p.x);
        camion.setY(p.y);
        camion.getHistory().add(new Point(p.x, p.y));
        // —> Evitar teletransportes si pasoActual superó rutaActual
        if (camion.getPasoActual() >= camion.getRutaActual().size()) {
            camion.getRutaActual().clear();
            camion.setPasoActual(0);
            //camion.getRutaPendiente().clear();
        }
    }

    /**
     * Define la ruta Manhattan de pasos a seguir.
     */
    @Override
    public void setRuta(CamionDinamico camion, List<Point> ruta) {
        camion.setRutaActual(new ArrayList<>(ruta));
        camion.setPasoActual(0);
    }

    /**
     * Añade un camino al historial (sin resetear). Ideal al comienzo.
     */
    @Override
    public void appendToHistory(CamionDinamico camion, List<Point> path) {
        if (camion.getHistory().isEmpty())
            camion.getHistory().add(new Point(camion.getX(), camion.getY()));
        if (path != null)
            camion.getHistory().addAll(path);
    }

    /**
     * Crea la flota dinámicamente a partir de los datos de CamionTemplateRepository.
     */
    public List<CamionDinamico> inicializarFlota() {
        List<CamionDinamico> flota = new ArrayList<>();
        for (CamionTemplate template : camionTemplateRepository.getTodos()) {
            CamionDinamico camion = new CamionDinamico(
                    template.getId(),
                    template.getCapacidadCarga(),
                    template.getTara(),
                    template.getCapacidadCombustible()
            );
            // inicializar estado dinámico
            camion.setDisponible(template.getCapacidadCarga());
            camion.setCombustibleDisponible(template.getCapacidadCombustible());
            flota.add(camion);
        }
        return flota;
    }
}
