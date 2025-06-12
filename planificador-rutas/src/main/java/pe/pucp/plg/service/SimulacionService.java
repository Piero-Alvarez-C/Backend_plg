package pe.pucp.plg.service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.dto.SimulationRequest;
import pe.pucp.plg.dto.SimulationStatusDTO;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.state.CamionDinamico;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.context.SimulacionEstado;
import pe.pucp.plg.service.algorithm.ACOPlanner;
import pe.pucp.plg.util.ParseadorArchivos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SimulacionService {

    @Autowired
    private SimulacionEstado estado;

    @Autowired
    private ACOPlanner acoPlanner;

    @Autowired
    private ArchivoService archivoService;

    @Autowired
    private CamionService camionService;

    @Autowired
    private TanqueService tanqueService;

    //@Autowired
    //private BloqueoService bloqueoService;

    /**
     * Reinicia simulación (delegar a SimulacionEstado).
     */
    public void iniciarSimulacion() {
        estado.setCurrentTime(0);
        // Reset camiones y tanques:
        estado.getCamiones().forEach(camion -> {
            camion.setDisponible(camion.getCapacidad());
            camion.setCombustibleDisponible(camion.getCapacidadCombustible());
            camion.setStatus(CamionDinamico.TruckStatus.AVAILABLE);
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

    //Para la simulación día a día
    public SimulationStatusDTO iniciarSimulacion(SimulationRequest request) {
        try {
            // 1. Cargar archivos por fileId
            String contenidoPedidos = new String(archivoService.obtenerArchivo(request.getFileIdPedidos()), StandardCharsets.UTF_8);
            String contenidoBloqueos = new String(archivoService.obtenerArchivo(request.getFileIdBloqueos()), StandardCharsets.UTF_8);
            String contenidoAverias = new String(archivoService.obtenerArchivo(request.getFileIdAverias()), StandardCharsets.UTF_8);

            // 2. Reiniciar tiempo y estado
            estado.setCurrentTime(0);

            // 3. Parsear pedidos
            Map<Integer, List<Pedido>> pedidosPorTiempo = ParseadorArchivos.parsearPedidosPorTiempo(contenidoPedidos);
            estado.setPedidosPorTiempo(pedidosPorTiempo);
            estado.setPedidos(new ArrayList<>());
            if (pedidosPorTiempo.containsKey(0)) {
                estado.getPedidos().addAll(pedidosPorTiempo.remove(0));
            }

            // 4. Parsear bloqueos
            List<Bloqueo> bloqueos = ParseadorArchivos.parsearBloqueos(contenidoBloqueos);
            estado.setBloqueos(bloqueos);

            // 5. Parsear averías por turno
            Map<String, Map<String, String>> averiasPorTurno = ParseadorArchivos.parsearAverias(contenidoAverias);
            estado.setAveriasPorTurno(averiasPorTurno);

            // 6. Cargar camiones
            estado.setCamiones(new ArrayList<>(camionService.inicializarFlota()));

            // 7. Inicializar tanques
            estado.setTanques(new ArrayList<>(tanqueService.inicializarTanques()));

            // 8. Limpiar estado
            estado.getEventosEntrega().clear();
            estado.getAveriasAplicadas().clear();
            estado.getCamionesInhabilitados().clear();
            estado.setRutas(new ArrayList<>());

            // 9. Generar ID de simulación y preparar respuesta
            String simulationId = UUID.randomUUID().toString();
            String nombre = request.getNombreSimulacion() != null ? request.getNombreSimulacion() : "Simulación sin nombre";

            SimulationStatusDTO status = new SimulationStatusDTO();
            status.setSimulationId(simulationId);
            status.setNombreSimulacion(nombre);
            status.setEstado("EN_PROGRESO");
            status.setAvance(0);

            return status;

        } catch (Exception e) {
            throw new RuntimeException("Error al iniciar simulación: " + e.getMessage(), e);
        }
    }
}
