package pe.pucp.plg.model.context;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.EntregaEvent;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.state.CamionDinamico;
import pe.pucp.plg.model.state.TanqueDinamico;
import pe.pucp.plg.service.BloqueoService;
import pe.pucp.plg.service.CamionService;
import pe.pucp.plg.service.TanqueService;
import pe.pucp.plg.util.ParseadorArchivos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
public class SimulacionEstado {

    // ————— Inyectar los servicios que proveen datos de camiones, tanques y bloqueos —————
    @Autowired
    private CamionService camionService;

    @Autowired @Lazy
    private TanqueService tanqueService;

    @Autowired @Lazy
    private BloqueoService bloqueoService;

    private AtomicInteger pedidoSeq = new AtomicInteger(1000);
    public int generateUniquePedidoId() {
        return pedidoSeq.getAndIncrement();
    }
    // 1) Estados de la flota
    private List<CamionDinamico> camiones = new ArrayList<>();

    // 2) Tanques intermedios
    private List<TanqueDinamico> tanques = new ArrayList<>();

    // 3) Pedidos (históricos + pendientes)
    private List<Pedido> pedidos = new ArrayList<>();

    // 4) Bloqueos cargados
    private List<Bloqueo> bloqueos = new ArrayList<>();

    // 5) Eventos de entrega futuros (se programan con tiempo de disparo)
    private List<EntregaEvent> eventosEntrega = new ArrayList<>();

    // 6) Mapa: minuto → lista de pedidos que llegan ese minuto
    private Map<Integer, List<Pedido>> pedidosPorTiempo = new HashMap<>();

    // 7) Averías por turno: ("T1"|"T2"|"T3") + camiónId → tipoAvería
    private Map<String, Map<String, String>> averiasPorTurno = new HashMap<>();

    // 8) Conjunto de IDs de averías aplicadas en el turno actual
    private Set<String> averiasAplicadas = new HashSet<>();

    // 9) IDs de camiones inhabilitados actualmente
    private Set<String> camionesInhabilitados = new HashSet<>();

    // 10) Depósito principal (coordenadas)
    private int depositoX = 12, depositoY = 8;

    // 11) Tiempo actual de la simulación (en minutos)
    private int currentTime = 0;

    // 12) Límite de tiempo máximo para simular (opcional)
    private int maxTime = Integer.MAX_VALUE;

    // 13) Turno anterior (para detectar cambio de turno)
    private String turnoAnterior = "";

    /** 14) Últimas rutas calculadas (para exponerlas en el snapshot) */
    private List<Ruta> rutas = new ArrayList<>();

    // ————— GETTERS y SETTERS (tal como los tienes) —————

    public List<CamionDinamico> getCamiones() { return camiones; }
    public void setCamiones(List<CamionDinamico> camiones) { this.camiones = camiones; }

    public List<TanqueDinamico> getTanques() { return tanques; }
    public void setTanques(List<TanqueDinamico> tanques) { this.tanques = tanques; }

    public List<Pedido> getPedidos() { return pedidos; }
    public void setPedidos(List<Pedido> pedidos) { this.pedidos = pedidos; }

    public List<Bloqueo> getBloqueos() { return bloqueos; }
    public void setBloqueos(List<Bloqueo> bloqueos) { this.bloqueos = bloqueos; }

    public List<EntregaEvent> getEventosEntrega() { return eventosEntrega; }
    public void setEventosEntrega(List<EntregaEvent> eventosEntrega) { this.eventosEntrega = eventosEntrega; }

    public Map<Integer, List<Pedido>> getPedidosPorTiempo() { return pedidosPorTiempo; }
    public void setPedidosPorTiempo(Map<Integer, List<Pedido>> pedidosPorTiempo) { this.pedidosPorTiempo = pedidosPorTiempo; }

    public Map<String, Map<String, String>> getAveriasPorTurno() { return averiasPorTurno; }
    public void setAveriasPorTurno(Map<String, Map<String, String>> averiasPorTurno) { this.averiasPorTurno = averiasPorTurno; }

    public Set<String> getAveriasAplicadas() { return averiasAplicadas; }
    public void setAveriasAplicadas(Set<String> averiasAplicadas) { this.averiasAplicadas = averiasAplicadas; }

    public Set<String> getCamionesInhabilitados() { return camionesInhabilitados; }
    public void setCamionesInhabilitados(Set<String> camionesInhabilitados) { this.camionesInhabilitados = camionesInhabilitados; }

    public int getDepositoX() { return depositoX; }
    public void setDepositoX(int depositoX) { this.depositoX = depositoX; }

    public int getDepositoY() { return depositoY; }
    public void setDepositoY(int depositoY) { this.depositoY = depositoY; }

    public int getCurrentTime() { return currentTime; }
    public void setCurrentTime(int currentTime) { this.currentTime = currentTime; }

    public int getMaxTime() { return maxTime; }
    public void setMaxTime(int maxTime) { this.maxTime = maxTime; }

    public String getTurnoAnterior() { return turnoAnterior; }
    public void setTurnoAnterior(String turnoAnterior) { this.turnoAnterior = turnoAnterior; }

    public List<Ruta> getRutas() { return rutas; }
    public void setRutas(List<Ruta> rutas) { this.rutas = rutas; }

    public TanqueDinamico obtenerTanquePorPosicion(int x, int y) {
        return tanques.stream()
                .filter(t -> t.getPosX() == x && t.getPosY() == y)
                .findFirst()
                .orElse(null);
    }


    // ————— Método que ya tenías para pedidos iniciales —————
    @PostConstruct
    public void init() {
        // —— 1️⃣ Pedidos ——
        try {
            var pedidoRes = new ClassPathResource("pedidos.txt");
            String contenidoPedidos = Files.readString(pedidoRes.getFile().toPath(), StandardCharsets.UTF_8);

            // Agrupo pedidos por tiempo de creación
            pedidosPorTiempo = ParseadorArchivos
                    .parsearPedidos(contenidoPedidos)
                    .stream()
                    .collect(Collectors.groupingBy(Pedido::getTiempoCreacion));

            // Inicializo pedidos activos (solo los de t=0)
            pedidos = new ArrayList<>();
            if (pedidosPorTiempo.containsKey(0)) {
                pedidos.addAll(pedidosPorTiempo.remove(0));
            }
            System.out.println("✅ Pedidos iniciales: " + pedidos.size());
        } catch (Exception e) {
            System.err.println("❌ Error cargando pedidos: " + e.getMessage());
            pedidosPorTiempo = new HashMap<>();
            pedidos = new ArrayList<>();
        }

        // —— 2️⃣ Camiones ——
        camiones = camionService.inicializarFlota();
        System.out.println("✅ Camiones cargados: " + camiones.size());

        // —— 3️⃣ Tanques ——
        tanques = tanqueService.inicializarTanques();
        System.out.println("✅ Tanques inicializados: " + tanques.size());

        // —— 4️⃣ Bloqueos ——
        try {
            var bloqueoRes = new ClassPathResource("bloqueos.txt");
            String contenidoBloqs = Files.readString(bloqueoRes.getFile().toPath(), StandardCharsets.UTF_8);
            bloqueos = ParseadorArchivos.parsearBloqueos(contenidoBloqs);
            System.out.println("✅ Bloqueos cargados: " + bloqueos.size());
        } catch (Exception e) {
            System.err.println("❌ Error cargando bloqueos: " + e.getMessage());
            bloqueos = new ArrayList<>();
        }

        // —— 5️⃣ Tiempo inicial ——
        currentTime = 0;
    }

}