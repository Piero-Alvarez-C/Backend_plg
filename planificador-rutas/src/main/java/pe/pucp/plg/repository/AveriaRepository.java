package pe.pucp.plg.repository;

import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Repository
public class AveriaRepository {

    // Mapa: T1, T2, T3 → { camiónId → tipoAvería }
    private final Map<String, Map<String, String>> averiasPorTurno = new HashMap<>();

    // Agrega un conjunto de averías para un turno
    public void agregar(String turno, Map<String, String> averiasTurno) {
        averiasPorTurno.put(turno, averiasTurno);
    }

    // Obtiene el mapa completo de averías por turno
    public Map<String, Map<String, String>> getAverias() {
        return Collections.unmodifiableMap(averiasPorTurno);
    }

    // Obtiene las averías específicas para un turno (T1, T2 o T3)
    public Map<String, String> getAveriasPorTurno(String turno) {
        return averiasPorTurno.getOrDefault(turno, Collections.emptyMap());
    }

    // Reinicia completamente las averías almacenadas
    public void reset() {
        averiasPorTurno.clear();
    }
}
