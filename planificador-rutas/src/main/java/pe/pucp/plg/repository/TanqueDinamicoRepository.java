package pe.pucp.plg.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import pe.pucp.plg.model.state.TanqueDinamico;
import pe.pucp.plg.model.template.TanqueTemplate;

import java.util.ArrayList;
import java.util.List;

@Repository
public class TanqueDinamicoRepository {

    private TanqueTemplateRepository tanqueTemplateRepository;
    private final List<TanqueDinamico> tanquesDinamicos = new ArrayList<>();

    @Autowired
    public TanqueDinamicoRepository(TanqueTemplateRepository tanqueTemplateRepository) {
        this.tanqueTemplateRepository = tanqueTemplateRepository;
    }

    @PostConstruct
    public void init() {
        for (TanqueTemplate template : tanqueTemplateRepository.getTodos()) {
            TanqueDinamico dinamico = new TanqueDinamico(template);
            tanquesDinamicos.add(dinamico);
        }
    }

    public List<TanqueDinamico> getTanques() {
        return tanquesDinamicos;
    }
}
