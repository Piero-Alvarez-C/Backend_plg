package pe.pucp.plg.service;

import pe.pucp.plg.model.state.TanqueDinamico;
import java.util.List;

public interface TanqueService {
    void reset(TanqueDinamico tanque);
    boolean puedeAbastecer(TanqueDinamico tanque, double volumen);
    void reducirCapacidad(TanqueDinamico tanque, double volumen);
    List<TanqueDinamico> inicializarTanques();
}
