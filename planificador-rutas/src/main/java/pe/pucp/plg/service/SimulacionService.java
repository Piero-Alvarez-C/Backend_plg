package pe.pucp.plg.service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.model.Camion;
import pe.pucp.plg.model.Pedido;
import pe.pucp.plg.state.SimulacionEstado;

import java.util.List;

@Service
public class SimulacionService {

    @Autowired
    private SimulacionEstado estado;

    @Autowired
    private ACOPlanner acoPlanner;

    /**
     * Reinicia simulación (delegar a SimulacionEstado).
     */
    public void iniciarSimulacion() {
        estado.setCurrentTime(0);
        // Reset camiones y tanques:
        estado.getCamiones().forEach(camion -> {
            camion.setDisponible(camion.getCapacidad());
            camion.setCombustibleDisponible(camion.getCapacidadCombustible());
            camion.setStatus(Camion.TruckStatus.AVAILABLE);
            camion.getHistory().clear();
            camion.getRutaPendiente().clear();
            camion.setPasoActual(0);
            camion.setEnRetorno(false);
            camion.setReabastecerEnTanque(null);
            // etc. según tu CamionService.reset(camion)
        });
        estado.getTanques().forEach(tanque -> tanque.setDisponible(tanque.getCapacidadTotal()));
        estado.getEventosEntrega().clear();
        estado.getAveriasAplicadas().clear();
        estado.getCamionesInhabilitados().clear();
        // (Opcional) Marcar todos los pedidos como no atendidos:
        estado.getPedidos().forEach(p -> {
            p.setAtendido(false);
            p.setDescartado(false);
            p.setProgramado(false);
        });
    }

    /**
     * Llama a ACOPlanner para que avance un minuto.
     * Retorna el nuevo tiempo actual.
     */
    public int stepOneMinute() {


        // 3️⃣ Delegar en el planificador ACO
        return acoPlanner.stepOneMinute();
    }

    public int getTiempoActual() {
        return estado.getCurrentTime();
    }
}
