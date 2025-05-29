package pe.pucp.plg.service;

import org.springframework.stereotype.Service;
import pe.pucp.plg.model.Camion;

import java.util.ArrayList;
import java.util.List;

@Service
public class CamionServiceImpl implements CamionService {

    private final List<Camion> flota = new ArrayList<>();

    public CamionServiceImpl() {
        inicializarFlota();
    }

    private void inicializarFlota() {
        // TA (2 unidades)
        flota.add(new Camion("TA01", "TA", 25, 2.5, 12.5));
        flota.add(new Camion("TA02", "TA", 25, 2.5, 12.5));

        // TB (4 unidades)
        for (int i = 1; i <= 4; i++)
            flota.add(new Camion(String.format("TB%02d", i), "TB", 15, 2.0, 7.5));

        // TC (4 unidades)
        for (int i = 1; i <= 4; i++)
            flota.add(new Camion(String.format("TC%02d", i), "TC", 10, 1.5, 5.0));

        // TD (10 unidades)
        for (int i = 1; i <= 10; i++)
            flota.add(new Camion(String.format("TD%02d", i), "TD", 5, 1.0, 2.5));
    }

    @Override
    public List<Camion> getFlota() {
        return flota;
    }
}
