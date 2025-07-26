package pe.pucp.plg.service.Orchest;

import java.util.List;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;

import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.context.ExecutionContext;

@Service
public class BlockageService {
    
    /**
     * Actualiza los bloqueos activos en el contexto para el tiempo actual.
     * Publica eventos de actualización de bloqueo si hay cambios.
     * 
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     * @param simulationId El ID de la simulación
     */
    public void actualizarBloqueosActivos(ExecutionContext contexto, LocalDateTime tiempoActual) {
        List<Bloqueo> bloqueosQueInicianAhora = contexto.getBloqueosPorTiempo().remove(tiempoActual);
        if (bloqueosQueInicianAhora != null) {
            for (Bloqueo b : bloqueosQueInicianAhora) {
                contexto.addBloqueoActivo(b);
                b.setLastKnownState(Bloqueo.Estado.ACTIVO);
                System.out.printf("🚧 Bloqueo activado: %s%n", b.getDescription());
            }
        }

        // 2. Revisar la lista de activos (que siempre es pequeña) para desactivar los que terminaron
        // Esta parte de tu lógica ya era eficiente y se mantiene.
        List<Bloqueo> bloqueosActivos = new ArrayList<>(contexto.getBloqueosActivos());
        for (Bloqueo b : bloqueosActivos) {
            if (!b.isActiveAt(tiempoActual)) {
                if (b.getLastKnownState() == Bloqueo.Estado.ACTIVO) {
                    contexto.removeBloqueoActivo(b);
                    b.setLastKnownState(Bloqueo.Estado.TERMINADO);
                    System.out.printf("✅ Bloqueo finalizado: %s%n", b.getDescription());
                }
            }
        }
    }
}
