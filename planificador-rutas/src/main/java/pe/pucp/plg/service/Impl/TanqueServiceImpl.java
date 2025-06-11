package pe.pucp.plg.service.Impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.model.state.TanqueDinamico;
import pe.pucp.plg.model.template.TanqueTemplate;
import pe.pucp.plg.repository.TanqueTemplateRepository;
import pe.pucp.plg.service.TanqueService;

import java.util.ArrayList;
import java.util.List;

@Service
public class TanqueServiceImpl implements TanqueService {

    @Autowired
    private TanqueTemplateRepository tanqueTemplateRepository;

    @Override
    public void reset(TanqueDinamico tanque) {
        tanque.setDisponible(tanque.getCapacidadTotal());
    }

    @Override
    public boolean puedeAbastecer(TanqueDinamico tanque, double volumen) {
        return tanque.getDisponible() >= volumen;
    }

    @Override
    public void reducirCapacidad(TanqueDinamico tanque, double volumen) {
        double nuevaCapacidad = tanque.getDisponible() - volumen;
        if (nuevaCapacidad < 0) nuevaCapacidad = 0;
        tanque.setDisponible(nuevaCapacidad);
    }

    @Override
    public List<TanqueDinamico> inicializarTanques() {
        List<TanqueDinamico> tanques = new ArrayList<>();

        for (TanqueTemplate plantilla : tanqueTemplateRepository.getTodos()) {
            tanques.add(new TanqueDinamico(plantilla));
        }

        return tanques;
    }
}
