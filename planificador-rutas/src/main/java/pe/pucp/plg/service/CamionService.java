package pe.pucp.plg.service;

import pe.pucp.plg.model.state.CamionDinamico;
import java.awt.Point;
import java.util.List;

public interface CamionService {
    void reset(CamionDinamico camion);
    void recargarCombustible(CamionDinamico camion);
    void avanzarUnPaso(CamionDinamico camion);
    void moverA(CamionDinamico camion, Point p);
    void setRuta(CamionDinamico camion, List<Point> ruta);
    void appendToHistory(CamionDinamico camion, List<Point> path);
    List<CamionDinamico> inicializarFlota();
}
