package pe.pucp.plg.service;

import pe.pucp.plg.model.Camion;
import java.awt.Point;
import java.util.List;

public interface CamionService {
    void reset(Camion camion);
    void recargarCombustible(Camion camion);
    void avanzarUnPaso(Camion camion);
    void moverA(Camion camion, Point p);
    void setRuta(Camion camion, List<Point> ruta);
    void appendToHistory(Camion camion, List<Point> path);
    List<Camion> listarTodos();
}
