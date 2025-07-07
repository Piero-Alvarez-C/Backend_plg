package pe.pucp.plg.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.factory.FlotaFactory;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.control.SimulationControlState;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.repository.BloqueoRepository;
import pe.pucp.plg.repository.PedidoRepository;

import java.time.LocalDateTime;
import java.util.NavigableMap;
import java.util.TreeMap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SimulationManagerService {

    private final FlotaFactory flotaFactory;
    private final TanqueService tanqueService;
    private final PedidoRepository pedidoRepository;
    private final BloqueoRepository bloqueoRepository;

    private ExecutionContext operationalContext;
    private ExecutionContext activeSimulationContext;
    private String activeSimulationId;
    private SimulationControlState activeSimulationControlState;

    @Autowired
    public SimulationManagerService(FlotaFactory flotaFactory,
                                    TanqueService tanqueService,
                                    PedidoRepository pedidoRepository,
                                    BloqueoRepository bloqueoRepository) {
        this.flotaFactory = flotaFactory;
        this.tanqueService = tanqueService;
        this.pedidoRepository = pedidoRepository;
        this.bloqueoRepository = bloqueoRepository;
    }

    @PostConstruct
    public void initializeOperationalContext() {
        this.operationalContext = new ExecutionContext();
        
        // 1. Initialize Camiones from FlotaFactory
        this.operationalContext.setCamiones(flotaFactory.crearNuevaFlota());

        // 2. Initialize Tanques
        this.operationalContext.setTanques(tanqueService.inicializarTanques()); 

        // 3. Initialize Pedidos from PedidoRepository
        LocalDateTime startTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        List<Pedido> todosLosPedidos = pedidoRepository.getAll(); // Assuming findAll() exists
        NavigableMap<LocalDateTime, List<Pedido>> pedidosPorTiempo = todosLosPedidos.stream()
            .filter(p -> p.getTiempoCreacion().isAfter(startTime)) // Filter out initial time pedidos for this map
            .collect(Collectors.groupingBy(Pedido::getTiempoCreacion, TreeMap::new, Collectors.toList()));
        this.operationalContext.setPedidosPorTiempo(pedidosPorTiempo);
        
        List<Pedido> initialPedidos = todosLosPedidos.stream()
            .filter(p -> p.getTiempoCreacion().equals(startTime))
            .collect(Collectors.toList());
        this.operationalContext.setPedidos(new ArrayList<>(initialPedidos)); 
        
        // 4. Initialize Bloqueos from BloqueoRepository
        this.operationalContext.setBloqueos(bloqueoRepository.getBloqueos()); // Assuming findAll() exists
        
        // 5. Set initial simulation time for operational context
        this.operationalContext.setCurrentTime(LocalDateTime.of(2025, 1, 1, 0, 0));
        // depositoX, depositoY have default values in ExecutionContext.
        // Other lists like averias, eventosEntrega, rutas will be empty initially.
    }

    public ExecutionContext getOperationalContext() {
        if (this.operationalContext == null) {
            throw new IllegalStateException("Operational context has not been initialized.");
        }
        return operationalContext;
    }

    /**
     * Creates a new simulation context, initialized with fresh data (similar to operational context).
     * If a simulation needs to start from the *current state* of the operational context,
     * then ExecutionContext should implement a deep cloning mechanism (e.g., a copy constructor),
     * and this method should clone the operationalContext instead.
     * @return The ID of the newly created simulation context.
     */
    public String crearContextoSimulacion() {
        if(activeSimulationContext != null) {
            throw new IllegalStateException("Ya hay una simulación activa con ID: " + activeSimulationId + ". No se puede crear otra.");
        }
        this.activeSimulationId = UUID.randomUUID().toString();
        this.activeSimulationContext = new ExecutionContext();
        this.activeSimulationControlState = new SimulationControlState();

        this.activeSimulationContext.setCamiones(flotaFactory.crearNuevaFlota()); 
        this.activeSimulationContext.setTanques(tanqueService.inicializarTanques());

        // Initialize Pedidos from PedidoRepository for the new simulation context
        LocalDateTime startTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        List<Pedido> todosLosPedidos = pedidoRepository.getAll(); // Assuming findAll() exists
        NavigableMap<LocalDateTime, List<Pedido>> pedidosPorTiempo = todosLosPedidos.stream()
            .filter(p -> p.getTiempoCreacion().isAfter(startTime)) // Filter out initial time pedidos for this map
            .collect(Collectors.groupingBy(Pedido::getTiempoCreacion, TreeMap::new, Collectors.toList()));
        this.activeSimulationContext.setPedidosPorTiempo(pedidosPorTiempo);

        List<Pedido> initialPedidos = todosLosPedidos.stream()
            .filter(p -> p.getTiempoCreacion().equals(startTime))
            .collect(Collectors.toList());
        this.activeSimulationContext.setPedidos(new ArrayList<>(initialPedidos));
        
        // Initialize Bloqueos from BloqueoRepository for the new simulation context
        this.activeSimulationContext.setBloqueos(bloqueoRepository.getBloqueos()); // Assuming findAll() exists
        this.activeSimulationContext.setCurrentTime(startTime); // Simulations typically start from t=0

        return this.activeSimulationId;
    }

    public ExecutionContext getActiveSimulationContext() {
        return this.activeSimulationContext;
    }

    public void destruirContextoSimulacion(String simulationId) {
        // Solo destruimos si el ID coincide con el de la simulación activa
        if (simulationId != null && simulationId.equals(this.activeSimulationId)) {
            this.activeSimulationContext = null;
            this.activeSimulationId = null;
            this.activeSimulationControlState = null;
            System.out.println("🗑️ Contexto de simulación destruido.");
        }
    }

    public SimulationControlState getActiveSimulationControlState() {
        return this.activeSimulationControlState;
    }

    public void pauseActiveSimulation() {
        if (activeSimulationControlState != null) {
            activeSimulationControlState.setPaused(true);
            System.out.println("⏯️ Simulación activa pausada.");
        }
    }

    public void resumeActiveSimulation() {
        if (activeSimulationControlState != null) {
            activeSimulationControlState.setPaused(false);
            System.out.println("▶️ Simulación activa reanudada.");
        }
    }

    public void setSpeedOfActiveSimulation(long delayMs) {
        if (activeSimulationControlState != null) {
            activeSimulationControlState.setStepDelayMs(delayMs);
            System.out.println("⏱️ Velocidad de simulación activa ajustada a " + delayMs + "ms de delay.");
        }
    }

}
