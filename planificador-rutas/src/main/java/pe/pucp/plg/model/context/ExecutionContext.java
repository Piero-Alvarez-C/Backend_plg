package pe.pucp.plg.model.context;

import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.EntregaEvent;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.common.Averia;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ExecutionContext {

    private AtomicInteger pedidoSeq = new AtomicInteger(1000);
    public String generateUniquePedidoId() {
        return String.valueOf(pedidoSeq.getAndIncrement());
    }
    // 1) Estados de la flota
    private List<CamionEstado> camiones = new ArrayList<>();

    // 2) Tanques intermedios
    private List<TanqueDinamico> tanques = new ArrayList<>();

    // 3) Pedidos (históricos + pendientes)
    private List<Pedido> pedidos = new ArrayList<>();

    // 4) Bloqueos cargados
    private NavigableMap<LocalDateTime, List<Bloqueo>> bloqueosPorTiempo = new TreeMap<>();

    // 5) Eventos de entrega futuros (se programan con tiempo de disparo)
    private Queue<EntregaEvent> eventosEntrega = new PriorityQueue<>(Comparator.comparing(ev -> ev.time));

    // 6) Mapa: tiempo → lista de pedidos que llegan ese tiempo
    private NavigableMap<LocalDateTime, List<Pedido>> pedidosPorTiempo = new TreeMap<>();

    // 7) Averías por turno: ("T1"|"T2"|"T3") + camiónId → tipoAvería
    private Map<String, Map<String, Averia>> averiasPorTurno = new HashMap<>();

    // 8) Conjunto de IDs de averías aplicadas en el turno actual
    private Set<String> averiasAplicadas = new HashSet<>();

    // 9) IDs de camiones inhabilitados actualmente
    private Set<String> camionesInhabilitados = new HashSet<>();

    // 9.1) Puntos de avería para camiones: idCamion_turno -> paso donde ocurrirá la avería
    private Map<String, Integer> puntosAveria = new HashMap<>();

    // 10) Depósito principal (coordenadas)
    private int depositoX = 12, depositoY = 8;

    // 11) Tiempo actual de la simulación
    private LocalDateTime currentTime;

    // 12) Límite de tiempo máximo para simular (opcional)
    private int maxTime = Integer.MAX_VALUE;

    // 13) Turno anterior (para detectar cambio de turno)
    private String turnoAnterior = "";

    // 14) Fecha de inicio de la simulación
    private LocalDate fechaInicio;

    // 15) Duración de la simulación en días
    private int duracionDias;

    /** 16) Últimas rutas calculadas (para exponerlas en el snapshot) */
    private List<Ruta> rutas = new ArrayList<>();

    private List<Bloqueo> bloqueosActivos = new ArrayList<>();

    private List<Bloqueo> bloqueosPorDia = new ArrayList<>();

    // PARA EL COLAPSO

    private boolean ignorarColapso = false;

    // PARA REPORTE FINAL
    private int totalPedidosEntregados = 0;
    private double totalDistanciaRecorrida = 0.0;
    private String pedidoColapso = null;

    // Mantenimientos
    private Map<LocalDate, String> mantenimientos = new HashMap<>();

    // ————— GETTERS y SETTERS (tal como los tienes) —————

    public Map<LocalDate, String> getMantenimientos() { return mantenimientos; }
    public void setMantenimientos(Map<LocalDate, String> mantenimientos) { this.mantenimientos = mantenimientos; }

    public int getTotalPedidosEntregados() { return totalPedidosEntregados; }
    public void setTotalPedidosEntregados(int totalPedidosEntregados) { this.totalPedidosEntregados = totalPedidosEntregados; }

    public double getTotalDistanciaRecorrida() { return totalDistanciaRecorrida; }
    public void setTotalDistanciaRecorrida(double totalDistanciaRecorrida) { this.totalDistanciaRecorrida = totalDistanciaRecorrida; }

    public String getPedidoColapso() { return pedidoColapso; }
    public void setPedidoColapso(String pedidoColapso) { this.pedidoColapso = pedidoColapso; }

    public NavigableMap<LocalDateTime, List<Bloqueo>> getBloqueosPorTiempo() { return bloqueosPorTiempo; }
    public void setBloqueosPorTiempo(NavigableMap<LocalDateTime, List<Bloqueo>> bloqueosPorTiempo) { this.bloqueosPorTiempo = bloqueosPorTiempo; }

    public List<Bloqueo> getBloqueosPorDia() { return bloqueosPorDia; }
    public void setBloqueosPorDia(List<Bloqueo> bloqueosPorDia) { this.bloqueosPorDia = bloqueosPorDia; }

    public List<CamionEstado> getCamiones() { return camiones; }
    public void setCamiones(List<CamionEstado> camiones) { this.camiones = camiones; }

    public List<TanqueDinamico> getTanques() { return tanques; }
    public void setTanques(List<TanqueDinamico> tanques) { this.tanques = tanques; }

    public List<Pedido> getPedidos() { return pedidos; }
    public void setPedidos(List<Pedido> pedidos) { this.pedidos = pedidos; }

    public Queue<EntregaEvent> getEventosEntrega() { return eventosEntrega; }
    public void setEventosEntrega(Queue<EntregaEvent> eventosEntrega) { this.eventosEntrega = eventosEntrega; }

    public NavigableMap<LocalDateTime, List<Pedido>> getPedidosPorTiempo() { return pedidosPorTiempo; }
    public void setPedidosPorTiempo(NavigableMap<LocalDateTime, List<Pedido>> pedidosPorTiempo) { this.pedidosPorTiempo = pedidosPorTiempo; }

    public Map<String, Map<String, Averia>> getAveriasPorTurno() { return averiasPorTurno; }
    public void setAveriasPorTurno(Map<String, Map<String, Averia>> averiasPorTurno) { this.averiasPorTurno = averiasPorTurno; }

    public Set<String> getAveriasAplicadas() { return averiasAplicadas; }
    public void setAveriasAplicadas(Set<String> averiasAplicadas) { this.averiasAplicadas = averiasAplicadas; }

    public Set<String> getCamionesInhabilitados() { return camionesInhabilitados; }
    public void setCamionesInhabilitados(Set<String> camionesInhabilitados) { this.camionesInhabilitados = camionesInhabilitados; }

    public Map<String, Integer> getPuntosAveria() { return puntosAveria; }
    public void setPuntosAveria(Map<String, Integer> puntosAveria) { this.puntosAveria = puntosAveria; }

    public int getDepositoX() { return depositoX; }
    public void setDepositoX(int depositoX) { this.depositoX = depositoX; }

    public int getDepositoY() { return depositoY; }
    public void setDepositoY(int depositoY) { this.depositoY = depositoY; }

    public LocalDateTime getCurrentTime() { return currentTime; }
    public void setCurrentTime(LocalDateTime currentTime) { this.currentTime = currentTime; }

    public int getMaxTime() { return maxTime; }
    public void setMaxTime(int maxTime) { this.maxTime = maxTime; }

    public String getTurnoAnterior() { return turnoAnterior; }
    public void setTurnoAnterior(String turnoAnterior) { this.turnoAnterior = turnoAnterior; }

    public List<Ruta> getRutas() { return rutas; }
    public void setRutas(List<Ruta> rutas) { this.rutas = rutas; }
    
    public LocalDate getFechaInicio() { return fechaInicio; }
    public void setFechaInicio(LocalDate fechaInicio) { this.fechaInicio = fechaInicio; }
    
    public int getDuracionDias() { return duracionDias; }
    public void setDuracionDias(int duracionDias) { this.duracionDias = duracionDias; }

    public boolean isIgnorarColapso() { return ignorarColapso; }
    public void setIgnorarColapso(boolean ignorarColapso) { this.ignorarColapso = ignorarColapso; }

    public TanqueDinamico obtenerTanquePorPosicion(int x, int y) {
        return tanques.stream()
                .filter(t -> t.getPosX() == x && t.getPosY() == y)
                .findFirst()
                .orElse(null);
    }

    public List<Bloqueo> getBloqueosActivos() {
        return bloqueosActivos;
    }
    public void setBloqueosActivos(List<Bloqueo> bloqueosActivos) {
        this.bloqueosActivos = bloqueosActivos;
    }
    public void addBloqueoActivo(Bloqueo bloqueo) {
        if (bloqueosActivos == null) {
            bloqueosActivos = new ArrayList<>();
        }
        if (bloqueo != null && !bloqueosActivos.contains(bloqueo)) {
            bloqueosActivos.add(bloqueo);
        }
    }
    public void removeBloqueoActivo(Bloqueo bloqueo) {
        if (bloqueosActivos == null) {
            bloqueosActivos = new ArrayList<>();
            return;
        }
        if (bloqueo != null) {
            bloqueosActivos.remove(bloqueo);
        }
    }

}