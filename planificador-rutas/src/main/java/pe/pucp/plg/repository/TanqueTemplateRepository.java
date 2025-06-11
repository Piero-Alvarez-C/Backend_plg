package pe.pucp.plg.repository;

import org.springframework.stereotype.Repository;
import pe.pucp.plg.model.template.TanqueTemplate;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Repository
public class TanqueTemplateRepository {

    private final List<TanqueTemplate> tanques = new ArrayList<>();

    @PostConstruct
    public void init() {
        tanques.clear();

        // 🟢 Tanque principal (almacén central)
        tanques.add(new TanqueTemplate(12, 8, 1000.0));

        // 🟡 Tanques intermedios
        tanques.add(new TanqueTemplate(42, 42, 160.0)); // Norte
        tanques.add(new TanqueTemplate(63, 3, 160.0));  // Este
    }

    public List<TanqueTemplate> getTodos() {
        return new ArrayList<>(tanques);
    }

    public void limpiar() {
        tanques.clear();
    }
}
