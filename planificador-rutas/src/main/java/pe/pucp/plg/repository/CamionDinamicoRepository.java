package pe.pucp.plg.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import pe.pucp.plg.model.state.CamionDinamico;
import pe.pucp.plg.model.template.CamionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Repositorio para inicializar la flota de camiones dinámicos
 * a partir de las plantillas definidas en CamionTemplateRepository.
 * Se usa únicamente en la simulación semanal (SimulacionEstado).
 */
@Component
public class CamionDinamicoRepository {

    private final CamionTemplateRepository templateRepository;
    private List<CamionDinamico> flota;

    @Autowired
    public CamionDinamicoRepository(CamionTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @PostConstruct
    public void init() {
        flota = new ArrayList<>();
        for (CamionTemplate plantilla : templateRepository.getTodos()) {
            CamionDinamico camion = new CamionDinamico(
                    plantilla.getId(),
                    plantilla.getCapacidadCarga(),
                    plantilla.getTara(),
                    plantilla.getCapacidadCombustible()
            );
            camion.setDisponible(plantilla.getCapacidadCarga());
            camion.setCombustibleDisponible(plantilla.getCapacidadCombustible());
            flota.add(camion);
        }
    }

    /**
     * Retorna una nueva copia de la flota inicial.
     */
    public List<CamionDinamico> obtenerFlota() {
        List<CamionDinamico> copia = new ArrayList<>();
        for (CamionDinamico original : flota) {
            CamionDinamico clon = new CamionDinamico(
                    original.getId(),
                    original.getCapacidad(),
                    original.getTara(),
                    original.getCapacidadCombustible()
            );
            clon.setDisponible(original.getCapacidad());
            clon.setCombustibleDisponible(original.getCapacidadCombustible());
            copia.add(clon);
        }
        return copia;
    }
}
