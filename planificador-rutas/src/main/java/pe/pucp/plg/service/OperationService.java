package pe.pucp.plg.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import pe.pucp.plg.dto.*;
import pe.pucp.plg.dto.enums.EventType;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Averia;
import pe.pucp.plg.model.context.ExecutionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors; 

import pe.pucp.plg.model.state.CamionEstado;

import pe.pucp.plg.util.MapperUtil;

@Service
public class OperationService {

    private final OrchestratorService orchestratorService;
    private final SimulationManagerService simulationManagerService;
    private final EventPublisherService eventPublisher;


    @Autowired
    public OperationService(OrchestratorService orchestratorService,
                            SimulationManagerService simulationManagerService,
                            EventPublisherService eventPublisher) {
        this.orchestratorService = orchestratorService;
        this.simulationManagerService = simulationManagerService;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void init() {
        simulationManagerService.initializeOperationalContext();
    }

    // Método que se ejecuta periódicamente para avanzar las operaciones día a día
    @Scheduled(fixedDelay = 10000) // cada 10 segundos fijo CAMBIAR A 60 SEGUNDOS CUANDO PASEMOS A PRODUCCION 
    public void ejecutarOperacionesDiaADia() {
        try {
            System.out.println("======================================OPERACIONES===================================================");
            orchestratorService.stepOneMinute(simulationManagerService.getOperationalContext(), "operational");
        } catch (Exception e) {
            System.err.println("Error al ejecutar paso de operaciones día a día: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Método para reiniciar el contexto operativo
    public void resetOperacional() {
        simulationManagerService.initializeOperationalContext();
    }

    // Método para obtener el contexto operativo actual
    public SimulacionSnapshotDTO getSnapshot() {
        return MapperUtil.toSnapshotDTO(simulationManagerService.getOperationalContext());
    }
    /**
     * Registers a new order into the operational context.
     * If the order's creation time matches the current operational time, it's added to active orders.
     * Otherwise, it's added to the time-based order map for future activation.
     * @param nuevoPedido The new order to register.
     */
    public void registrarNuevoPedido(Pedido nuevoPedido) {
        ExecutionContext operationalContext = simulationManagerService.getOperationalContext();
        if (operationalContext == null) {
            throw new IllegalStateException("Operational context is not available.");
        }

        // Ensure the pedido has a unique ID if not already set
        if (nuevoPedido.getId() == 0) { // Assuming 0 or a specific value indicates a new, ID-less pedido
            nuevoPedido.setId(operationalContext.generateUniquePedidoId());
        }

        if (nuevoPedido.getTiempoCreacion().equals(operationalContext.getCurrentTime())) {
            List<Pedido> listaPedidos = new ArrayList<>();//para que se procese el pedido con tiempo en stepOneMinute
            listaPedidos.add(nuevoPedido);
            operationalContext.getPedidosPorTiempo().put(nuevoPedido.getTiempoCreacion().plusMinutes(1), listaPedidos);
            System.out.println("Sí agregó el pedido a la lista para ser atendido en el tiempo: "+nuevoPedido.getTiempoCreacion());
        } else if (nuevoPedido.getTiempoCreacion().isAfter(operationalContext.getCurrentTime())) {
            operationalContext.getPedidosPorTiempo()
                .computeIfAbsent(nuevoPedido.getTiempoCreacion(), k -> new ArrayList<>()).add(nuevoPedido);
            System.out.println("Hizo un cambio en el pedido a ser atendido, nuevo tiempo de creacion: "+ nuevoPedido.getTiempoCreacion());
        } else {
            // Handle or log pedidos created in the past if necessary, 
            // for now, adding to current pedidos if it's somehow missed.
            System.err.println("Warning: Registering a pedido with a creation time in the past: " + nuevoPedido.getId() 
                + " at time " + nuevoPedido.getTiempoCreacion() + " while current time is " + operationalContext.getCurrentTime());
            operationalContext.getPedidos().add(nuevoPedido);
        }
        // Potentially trigger re-planning or notify other components
    }

    /**
     * Registers a vehicle breakdown in the operational context.
     * @param camionId The ID of the truck that broke down.
     * @param tipoAveria A description or type of the breakdown.
     * @param turno El turno en que ocurrió la avería ("T1", "T2", "T3")
     */
    public Averia registrarAveriaCamion(AveriaDTO dto) {
        ExecutionContext operationalContext = simulationManagerService.getOperationalContext();
        if (operationalContext == null) {
            throw new IllegalStateException("Operational context is not available.");
        }
        String turno= dto.getTurno();
        String camionId= dto.getCodigoVehiculo();
        String tipoAveria= dto.getTipoIncidente();
        Averia nuevAveria= new Averia(turno,camionId,tipoAveria);
        operationalContext.getAveriasPorTurno()
                .computeIfAbsent(turno, k -> new java.util.HashMap<>()).put(camionId, nuevAveria);

    
        // Mark the truck as unavailable if the breakdown is for the current/active turn
        // This logic might be more complex depending on how turns are managed vs.
        // currentTime
        // For simplicity, if an averia is registered, we might immediately mark the
        // truck as inhabilitado.
        operationalContext.getCamionesInhabilitados().add(camionId);

        // Find the CamionEstado and update its status if necessary
        operationalContext.getCamiones().stream()
                .filter(c -> c.getPlantilla().getId().equals(camionId))
                .findFirst()
                .ifPresent(camion -> {
                    System.out.println("Estado antes: " + camion.getStatus());
                    camion.setStatus(CamionEstado.TruckStatus.BREAKDOWN);
                    System.out.println("Estado después: " + camion.getStatus());
                    System.out.println(
                            "Camion " + camionId + " marked with averia: " + tipoAveria + " for turno " + turno);
                });
        // Potentially trigger re-planning
        return nuevAveria;
    }

    public List<CamionDTO> getListaCamionesOperacionalesDTO() {
            ExecutionContext operationalContext = simulationManagerService.getOperationalContext();
        if (operationalContext == null) {
            throw new IllegalStateException("Operational context is not available.");
        }
        if (operationalContext.getCamiones() == null) {
            return new ArrayList<>();
        }
        return operationalContext.getCamiones().stream()
                .map(MapperUtil::toCamionDTO)
                .collect(Collectors.toList());
    }

    public List<PedidoDTO> getPedidosOperacionalesDTO() { // Added method
        ExecutionContext operationalContext = simulationManagerService.getOperationalContext();
        if (operationalContext == null) {
            throw new IllegalStateException("Operational context is not available.");
        }
        if (operationalContext.getPedidos() == null) {
            return new ArrayList<>();
        }
        return operationalContext.getPedidos().stream()
                .map(MapperUtil::toPedidoDTO)
                .collect(Collectors.toList());
    }

    public Pedido crearNuevoPedidoOperacional(PedidoDTO dto) { // Added method
        ExecutionContext operationalContext = simulationManagerService.getOperationalContext();
        if (operationalContext == null) {
            throw new IllegalStateException("Operational context is not available.");
        }
        int id = operationalContext.generateUniquePedidoId();
        Pedido nuevo = new Pedido(id,
                operationalContext.getCurrentTime(), // Use operational context's current time
                dto.getX(), dto.getY(),
                dto.getVolumen(), dto.getTiempoLimite());
        
        // Use the existing registrarNuevoPedido logic which handles adding to current or future pedidos
        registrarNuevoPedido(nuevo);
        PedidoDTO nuevoDTO = MapperUtil.toPedidoDTO(nuevo);
        EventDTO evento = EventDTO.of(EventType.ORDER_CREATED, nuevoDTO);
        eventPublisher.publicarEventoOperacion(evento);
        return nuevo;
    }

    public List<BloqueoDTO> getBloqueosOperacionalesDTO() { // Added method
        ExecutionContext operationalContext = simulationManagerService.getOperationalContext();
        if (operationalContext == null) {
            throw new IllegalStateException("Operational context is not available.");
        }
        if (operationalContext.getBloqueosActivos() == null) {
            return new ArrayList<>();
        }
        return operationalContext.getBloqueosActivos().stream()
                .map(MapperUtil::toBloqueoDTO)
                .collect(Collectors.toList());
    }

    public List<TanqueDTO> getTanquesOperacionalesDTO() { // Added method
        ExecutionContext operationalContext = simulationManagerService.getOperationalContext();
        if (operationalContext == null) {
            throw new IllegalStateException("Operational context is not available.");
        }
        if (operationalContext.getTanques() == null) {
            return new ArrayList<>();
        }
        return operationalContext.getTanques().stream()
                .map(MapperUtil::toTanqueDTO)
                .collect(Collectors.toList());
    }

    // Placeholder for registering an event - to be used by OperacionesController
    public void registrarEventoOperacional(Object evento) {
        ExecutionContext operationalContext = simulationManagerService.getOperationalContext();
        if (operationalContext == null) {
            throw new IllegalStateException("Operational context is not available.");
        }
        // Logic to handle generic events in the operational context using 'operationalContext'
        System.out.println("Evento '" + evento.toString() + "' registrado en contexto operacional. Context time: " + operationalContext.getCurrentTime());
        // Example: if (evento instanceof AveriaCamionInput) { 
        //    AveriaCamionInput averiaInput = (AveriaCamionInput) evento;
        //    operationalContext.getAveriasPorTurno()
        //        .computeIfAbsent(averiaInput.getTurno(), k -> new java.util.HashMap<>()).put(averiaInput.getCamionId(), averiaInput.getTipoAveria());
        //    operationalContext.getCamionesInhabilitados().add(averiaInput.getCamionId());
        // }
    }
}
