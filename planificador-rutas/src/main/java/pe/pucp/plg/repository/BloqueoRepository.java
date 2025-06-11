package pe.pucp.plg.repository;

import org.springframework.stereotype.Repository;
import pe.pucp.plg.model.common.Bloqueo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class BloqueoRepository {

    private final List<Bloqueo> bloqueos = new ArrayList<>();

    // Agregar un solo bloqueo
    public void agregar(Bloqueo bloqueo) {
        bloqueos.add(bloqueo);
    }

    // Agregar múltiples bloqueos (desde archivo, por ejemplo)
    public void agregarTodos(List<Bloqueo> nuevos) {
        bloqueos.addAll(nuevos);
    }

    // Obtener todos los bloqueos actuales
    public List<Bloqueo> getBloqueos() {
        return Collections.unmodifiableList(bloqueos);
    }

    // Limpiar la lista de bloqueos
    public void reset() {
        bloqueos.clear();
    }
}
