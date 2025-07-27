package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.dto.*;
import pe.pucp.plg.dto.enums.EventType;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.service.Orchest.BlockageService;
import pe.pucp.plg.service.Orchest.DeliveryEventService;
import pe.pucp.plg.service.Orchest.FleetService;
import pe.pucp.plg.service.Orchest.IncidentService;
import pe.pucp.plg.service.Orchest.MaintenanceService;
import pe.pucp.plg.service.Orchest.PathfindingService;
import pe.pucp.plg.service.Orchest.PlanningService;
import pe.pucp.plg.service.Orchest.SimulationStateService;
import pe.pucp.plg.service.algorithm.ACOPlanner;
import pe.pucp.plg.util.MapperUtil;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrchestratorService {

    private final SimulationStateService stateService;
    private final IncidentService incidentService;
    private final MaintenanceService maintenanceService;
    private final BlockageService blockageService;
    private final FleetService fleetService;
    private final DeliveryEventService eventService;
    private final PlanningService planningService;
    private final PathfindingService pathfindingService;
    private final ACOPlanner acoPlanner;
    private final EventPublisherService eventPublisher;

    private static final int UMBRAL_VENCIMIENTO = 90;
    private static final int INTERVALO_REPLAN = 60; 

    private int countReplan = 0;

    @Autowired // 👈 Anota el constructor
    public OrchestratorService(
        SimulationStateService stateService,
        IncidentService incidentService,
        MaintenanceService maintenanceService,
        BlockageService blockageService,
        FleetService fleetService,
        DeliveryEventService eventService,
        PlanningService planningService,
        EventPublisherService eventPublisher,
        PathfindingService pathfindingService,
        ACOPlanner acoPlanner
    ) {
        this.stateService = stateService;
        this.incidentService = incidentService;
        this.maintenanceService = maintenanceService;
        this.blockageService = blockageService;
        this.fleetService = fleetService;
        this.eventService = eventService;
        this.planningService = planningService;
        this.eventPublisher = eventPublisher;
        this.pathfindingService = pathfindingService;
        this.acoPlanner = acoPlanner;
        this.countReplan = 0;
    }

    /**
     * Advances the simulation identified by simulationId by one minute.
     * @param simulationId The ID of the simulation to step forward.
     * @return The new current time of the simulation.
     */
    public LocalDateTime stepOneMinute(ExecutionContext contexto, String simulationId) {
        // corregido
        LocalDateTime tiempoActual = contexto.getCurrentTime() != null ?
                contexto.getCurrentTime().plusMinutes(1).withSecond(0).withNano(0) : LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        contexto.setCurrentTime(tiempoActual);
        // Para verificaciones que necesitan un día nuevo
        boolean esMediaNoche = tiempoActual.getHour() == 0 && tiempoActual.getMinute() == 0 && !tiempoActual.equals(contexto.getFechaInicio().atStartOfDay());
        boolean replanificar = tiempoActual.equals(contexto.getFechaInicio().atStartOfDay()); // Replanificar al inicio siempre

        // 1) Recarga de tanques intermedios cada vez que currentTime % 1440 == 0 (inicio de día)
        if (esMediaNoche) {
            stateService.accionesMediaNoche(contexto, tiempoActual, replanificar);
        }
        countReplan++;

        maintenanceService.processMaintenances(contexto, tiempoActual);

        blockageService.actualizarBloqueosActivos(contexto, tiempoActual);        

        // 3) Avanzar o procesar retorno y entregas por separado
        fleetService.avanzar(contexto, tiempoActual);
        // 2) Disparar eventos de entrega programados para este minuto
        eventService.triggerScheduledDeliveries(tiempoActual, contexto);
        stateService.inyectarPedidos(contexto, tiempoActual, replanificar);

        // Detectar si hay pedidos NUEVOS en este minuto y forzar replanificación
        //boolean hayNuevos = contexto.getPedidos().stream().anyMatch(p -> p.getTiempoCreacion().equals(tiempoActual));
        //if (hayNuevos) {
        //    replanificar = true;
            //System.out.printf("Pedido nuevo en t=%s → replanificar inmediato%n", tiempoActual);
        //}
        if ((countReplan % INTERVALO_REPLAN == 0 || countReplan == INTERVALO_REPLAN) && !replanificar) { 
            System.out.println("Replanificando");   
            replanificar = true;
            countReplan = 0;
        }

        // (B) pedidos próximos a vencer: umbral en minutos
        boolean hayUrgentes = contexto.getPedidos().stream()
                .filter(p -> !p.isAtendido() && !p.isDescartado())
                .anyMatch(p -> p.getTiempoLimite().minusMinutes(UMBRAL_VENCIMIENTO).isBefore(tiempoActual));
        if (hayUrgentes) {
            replanificar = true;
        }

        if(stateService.hayColapso(contexto, tiempoActual) && !contexto.isIgnorarColapso()) {
            // Si ha colapsado y no se ignora, destruir la simulacion
            System.out.printf("💥 Colapso detectado en %s, finalizando simulación%n", tiempoActual);
            eventPublisher.publicarEventoSimulacion(simulationId, EventDTO.of(EventType.SIMULATION_COLLAPSED, new ReporteDTO(contexto.getTotalPedidosEntregados(), contexto.getTotalDistanciaRecorrida(), contexto.getPedidoColapso())));
            return null;
        }

        // 7) Averías por turno (T1, T2, T3)
        replanificar |= incidentService.procesarAverias(contexto, tiempoActual);

        // 8) Construir estado “ligero” de la flota disponible para ACO
        List<CamionEstado> flotaEstado = contexto.getCamiones().stream()
                .filter(c -> c.getStatus() != CamionEstado.TruckStatus.UNAVAILABLE
                        && c.getStatus() != CamionEstado.TruckStatus.BREAKDOWN
                        && c.getStatus() != CamionEstado.TruckStatus.MAINTENANCE
                        && c.getPedidosCargados().isEmpty()            // no tiene entregas encoladas
                        && c.getPedidoDesvio() == null)               // no está en medio de un desvío
                .map(c -> {
                    CamionEstado est = new CamionEstado(c);
                    return est;
                })
                .collect(Collectors.toList());

        if (replanificar && flotaEstado.isEmpty()) {
            if(flotaEstado.isEmpty()) {
                System.out.printf("Ningún camión disponible (ni en ventana) → replanificación pospuesta%n",
                                    tiempoActual);
            }
            //replanificar = false;
        }

        // 9) Determinar candidatos a replanificar
        List<Pedido> pendientes = contexto.getPedidos().stream()
                .filter(p -> !p.isAtendido() && !p.isDescartado() && !p.isProgramado() && p.getTiempoCreacion().isBefore(tiempoActual))
                .collect(Collectors.toList());
        pendientes.sort(Comparator.comparing(Pedido::getTiempoLimite));
        List<Pedido> candidatos = pendientes;
        System.out.printf(
                "⏲️ Replanificando en t=%s → camionesDisponibles=%d, pedidosPendientes=%d%n",
                tiempoActual,
                flotaEstado.size(),
                candidatos.size()
        );
        // 10) Replanificación ACO si procede
        planningService.replanificar(contexto, flotaEstado, candidatos, tiempoActual, replanificar);
        System.out.printf(
                "⏲️  stepOneMinute t=%s → flotaDisponibles=%d, pedidosPendientes=%d, replan=%s%n",
                tiempoActual,
                flotaEstado.size(),
                candidatos.size(),
                replanificar
        );
        stateService.actualizarPedidosPorTanque(contexto);

        EventDTO estadoActual = EventDTO.of(EventType.SNAPSHOT, MapperUtil.toSnapshotDTO(contexto));
        eventPublisher.publicarEventoSimulacion(simulationId, estadoActual);

        return contexto.getCurrentTime();        
    }    

}