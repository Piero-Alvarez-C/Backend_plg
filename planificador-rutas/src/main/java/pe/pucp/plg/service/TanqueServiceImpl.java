package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.model.Tanque;
import pe.pucp.plg.state.SimulacionEstado;

import java.util.ArrayList;
import java.util.List;

@Service
public class TanqueServiceImpl implements TanqueService {

    @Autowired
    private SimulacionEstado simulacionEstado;

    @Override
    public void reset(Tanque tanque) {
        tanque.setDisponible(tanque.getCapacidadTotal());
    }

    @Override
    public boolean puedeAbastecer(Tanque tanque, double volumen) {
        return tanque.getDisponible() >= volumen;
    }

    @Override
    public void reducirCapacidad(Tanque tanque, double volumen) {
        double nuevaCapacidad = tanque.getDisponible() - volumen;
        if (nuevaCapacidad < 0) nuevaCapacidad = 0;
        tanque.setDisponible(nuevaCapacidad);
    }

    @Override
    public Tanque obtenerPorPosicion(int x, int y) {
        return simulacionEstado.getTanques().stream()
                .filter(t -> t.getPosX() == x && t.getPosY() == y)
                .findFirst()
                .orElse(null);
    }
    @Override
    public List<Tanque> listarTodos() {
        return simulacionEstado.getTanques();
    }
}
