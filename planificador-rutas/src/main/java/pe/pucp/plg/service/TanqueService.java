package pe.pucp.plg.service;

import pe.pucp.plg.model.Tanque;
import java.util.List;

public interface TanqueService {
    void reset(Tanque tanque);
    boolean puedeAbastecer(Tanque tanque, double volumen);
    void reducirCapacidad(Tanque tanque, double volumen);
    Tanque obtenerPorPosicion(int x, int y);
}
