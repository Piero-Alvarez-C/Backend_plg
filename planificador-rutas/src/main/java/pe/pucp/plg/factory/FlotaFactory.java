package pe.pucp.plg.factory;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.template.CamionTemplate;
import pe.pucp.plg.repository.CamionTemplateRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Factory para crear instancias iniciales de la flota de camiones.
 * Los camiones se inicializan en una posición por defecto (depósito)
 * y con el combustible lleno.
 */
@Component
public class FlotaFactory {

    private final CamionTemplateRepository templateRepository;
    private List<CamionEstado> flotaInicialConfigurada;

    // Coordenadas por defecto para la inicialización de los camiones 
    private static final int DEFAULT_INITIAL_X = 12;
    private static final int DEFAULT_INITIAL_Y = 8;

    @Autowired
    public FlotaFactory(CamionTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @PostConstruct
    public void init() {
        flotaInicialConfigurada = new ArrayList<>();
        for (CamionTemplate plantilla : templateRepository.getTodos()) {
            CamionEstado camion = new CamionEstado(plantilla, DEFAULT_INITIAL_X, DEFAULT_INITIAL_Y);
            flotaInicialConfigurada.add(camion);
        }
    }

    /**
     * Retorna una nueva copia (deep clone) de la flota inicial configurada.
     * Cada camión en la lista devuelta es una nueva instancia clonada.
     */
    public List<CamionEstado> crearNuevaFlota() {
        if (flotaInicialConfigurada == null) {
            init(); 
        }
        return flotaInicialConfigurada.stream()
                .map(CamionEstado::deepClone)
                .collect(Collectors.toList());
    }

    /**
     * Retorna la lista de plantillas de camiones disponibles.
     * Útil si se necesita acceder a las plantillas base.
     */
    public List<CamionTemplate> getPlantillasDeCamiones() {
        return templateRepository.getTodos();
    }
}
