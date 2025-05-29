package pe.pucp.plg.service;

import org.springframework.stereotype.Service;
import pe.pucp.plg.model.Tanque;

import java.util.ArrayList;
import java.util.List;

@Service
public class TanqueServiceImpl implements TanqueService {

    private final List<Tanque> tanques = new ArrayList<>();

    public TanqueServiceImpl() {
        tanques.add(new Tanque("Almacén Principal", 12, 8, Double.POSITIVE_INFINITY, true));
        tanques.add(new Tanque("Tanque Intermedio Norte", 42, 42, 50.0, false));
        tanques.add(new Tanque("Tanque Intermedio Este", 63, 3, 10.0, false));
    }

    @Override
    public List<Tanque> obtenerTanques() {
        return tanques;
    }
}
