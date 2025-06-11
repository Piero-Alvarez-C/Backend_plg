package pe.pucp.plg.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import pe.pucp.plg.model.template.CamionTemplate;

import java.util.ArrayList;
import java.util.List;

@Repository
public class CamionTemplateRepository {

    private final List<CamionTemplate> camiones = new ArrayList<>();

    @PostConstruct
    public void init() {
        camiones.clear();

        // 🟢 Combustible fijo por camión: 25 galones
        double combustible = 25.0;

        // Tipo TA: 2 unidades (25 m³, 2.5 t)
        for (int i = 1; i <= 2; i++) {
            camiones.add(new CamionTemplate("TA" + i, 25, 2.5, combustible));
        }

        // Tipo TB: 4 unidades (15 m³, 2.0 t)
        for (int i = 1; i <= 4; i++) {
            camiones.add(new CamionTemplate("TB" + i, 15, 2.0, combustible));
        }

        // Tipo TC: 4 unidades (10 m³, 1.5 t)
        for (int i = 1; i <= 4; i++) {
            camiones.add(new CamionTemplate("TC" + i, 10, 1.5, combustible));
        }

        // Tipo TD: 10 unidades (5 m³, 1.0 t)
        for (int i = 1; i <= 10; i++) {
            camiones.add(new CamionTemplate("TD" + i, 5, 1.0, combustible));
        }
    }

    public List<CamionTemplate> getTodos() {
        return new ArrayList<>(camiones);
    }

    public void limpiar() {
        camiones.clear();
    }
}
