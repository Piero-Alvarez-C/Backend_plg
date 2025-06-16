package pe.pucp.plg.model.state;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.template.CamionTemplate;

public class CamionEstado {

    public enum TruckStatus {
        AVAILABLE,      // Disponible para nuevas asignaciones
        IDLE,           // En el depósito, sin hacer nada pero disponible en breve
        IN_TRANSIT,     // En camino (general)
        MOVING_TO_DELIVER, // En camino a un punto de entrega específico
        DELIVERING,     // En proceso de descarga en un punto de entrega
        RETURNING,      // Regresando al depósito o a un tanque
        REFUELING,      // En proceso de recarga de combustible
        LOADING,        // En proceso de carga (si aplica, ej. en depósito para múltiples pedidos)
        OUT_OF_SERVICE, // Fuera de servicio (ej. sin combustible, averiado)
        MAINTENANCE     // En mantenimiento programado
    }

    private final CamionTemplate plantilla;
    private Point posicionActual;
    private double combustibleActual;
    private double cargaActualVolumen;
    private TruckStatus status;
    private int tiempoOcupadoHasta; 

    private List<Pedido> pedidosCargados; // Pedidos actualmente en el camión
    private List<Point> rutaActual; 
    private int proximoPuntoRutaIndex;
    private List<Pedido> pedidosAsignadosEnRutaActual; // Secuencia de pedidos a entregar en la ruta actual
    private TanqueDinamico tanqueDestinoRecarga; 
    
    private double consumoTotalRutaProvisional; 

    public CamionEstado(CamionTemplate plantilla, int initialX, int initialY) {
        this.plantilla = Objects.requireNonNull(plantilla, "La plantilla del camión no puede ser nula.");
        this.posicionActual = new Point(initialX, initialY);
        this.combustibleActual = plantilla.getCapacidadCombustible();
        this.cargaActualVolumen = 0;
        this.pedidosCargados = new ArrayList<>();
        this.rutaActual = new ArrayList<>();
        this.proximoPuntoRutaIndex = -1;
        this.pedidosAsignadosEnRutaActual = new ArrayList<>();
        this.tanqueDestinoRecarga = null;
        this.status = TruckStatus.IDLE; 
        this.tiempoOcupadoHasta = 0; 
        this.consumoTotalRutaProvisional = 0;
    }

    public CamionEstado(CamionEstado original) {
        this.plantilla = original.plantilla; 
        this.posicionActual = new Point(original.posicionActual.x, original.posicionActual.y);
        this.combustibleActual = original.combustibleActual;
        this.cargaActualVolumen = original.cargaActualVolumen;
        this.pedidosCargados = new ArrayList<>();
        for (Pedido p : original.pedidosCargados) {
            this.pedidosCargados.add(new Pedido(p)); 
        }
        this.rutaActual = new ArrayList<>();
        for (Point p : original.rutaActual) {
            this.rutaActual.add(new Point(p.x, p.y)); 
        }
        this.proximoPuntoRutaIndex = original.proximoPuntoRutaIndex;
        this.pedidosAsignadosEnRutaActual = new ArrayList<>();
        for (Pedido p : original.pedidosAsignadosEnRutaActual) {
            this.pedidosAsignadosEnRutaActual.add(new Pedido(p));
        }
        this.tanqueDestinoRecarga = (original.tanqueDestinoRecarga != null) ? new TanqueDinamico(original.tanqueDestinoRecarga) : null; 
        this.status = original.status;
        this.tiempoOcupadoHasta = original.tiempoOcupadoHasta;
        this.consumoTotalRutaProvisional = original.consumoTotalRutaProvisional;
    }

    // Getters
    public CamionTemplate getPlantilla() { return plantilla; }
    public Point getPosicionActual() { return posicionActual; }
    public double getCombustibleActual() { return combustibleActual; }
    public double getCargaActualVolumen() { return cargaActualVolumen; }
    public List<Pedido> getPedidosCargados() { return Collections.unmodifiableList(pedidosCargados); }
    public List<Point> getRutaActual() { return Collections.unmodifiableList(rutaActual); }
    public int getProximoPuntoRutaIndex() { return proximoPuntoRutaIndex; }
    public List<Pedido> getPedidosAsignadosEnRutaActual() { return Collections.unmodifiableList(pedidosAsignadosEnRutaActual); }
    public TanqueDinamico getTanqueDestinoRecarga() { return tanqueDestinoRecarga; } // Alias for getTanqueDestinoRuta
    public TruckStatus getStatus() { return status; }
    public double getCapacidadVolumetricaDisponible() { return plantilla.getCapacidadCarga() - cargaActualVolumen; }
    public int getTiempoLibre(int tiempoActual) { return Math.max(0, tiempoOcupadoHasta - tiempoActual); }
    public double getConsumoTotalRutaProvisional() { return consumoTotalRutaProvisional; }

    // Setters for cloned instances used by ACOPlanner
    public void setTiempoLibre(int nuevoTiempoOcupadoHasta) { 
        this.tiempoOcupadoHasta = nuevoTiempoOcupadoHasta; 
    }
    public void setPosicionActual(Point nuevaPosicion) { 
        this.posicionActual = new Point(nuevaPosicion.x, nuevaPosicion.y);
    }
    public void setStatus(TruckStatus newStatus) {
        this.status = newStatus;
    }

    public void registrarCargaPedido(Pedido p) { 
        if (getCapacidadVolumetricaDisponible() >= p.getVolumen()) {
            this.cargaActualVolumen += p.getVolumen();
        } 
    }

    public void reset(int initialX, int initialY) {
        this.posicionActual = new Point(initialX, initialY);
        this.combustibleActual = plantilla.getCapacidadCombustible();
        this.cargaActualVolumen = 0;
        this.pedidosCargados.clear();
        this.rutaActual.clear();
        this.proximoPuntoRutaIndex = -1;
        this.pedidosAsignadosEnRutaActual.clear();
        this.tanqueDestinoRecarga = null;
        this.status = TruckStatus.IDLE;
        this.tiempoOcupadoHasta = 0;
        this.consumoTotalRutaProvisional = 0;
    }

    public boolean tieneRutaAsignada() {
        return proximoPuntoRutaIndex != -1 && !rutaActual.isEmpty();
    }
    
    public boolean estaEnDestinoDeRuta() {
        if (!tieneRutaAsignada()) return true;
        return proximoPuntoRutaIndex >= rutaActual.size();
    }

    private double calcularDistanciaManhattan(Point p1, Point p2) {
        return Math.abs(p1.x - p2.x) + Math.abs(p1.y - p2.y);
    }

    public void avanzarPasoEnRutaActual() {
        // 1. Check if the truck can move:
        //      The simplified version assumes that if a truck has a route and is told to advance,
        //      its status is already appropriate for movement (e.g., IN_TRANSIT, MOVING_TO_DELIVER, RETURNING).
        //      More robust status checking before calling this method should be done by the caller.

        if (!tieneRutaAsignada() || estaEnDestinoDeRuta()) {
            // No route, or already at the end of the current path. Nothing to do here.
            return;
        }

        if (combustibleActual <= 0) {
            // Ran out of fuel. Cannot move.
            // The status (e.g., OUT_OF_SERVICE) should be managed by the planner.
            return;
        }

        // 2. Get the next point in the route.
        // rutaActual and proximoPuntoRutaIndex have been validated by tieneRutaAsignada() and !estaEnDestinoDeRuta()
        Point proximoPunto = rutaActual.get(proximoPuntoRutaIndex);

        // Store current position before moving
        Point posicionAnterior = new Point(this.posicionActual.x, this.posicionActual.y);

        // 3. Move to the next point.
        // This assumes that each point in 'rutaActual' represents a location reachable in one "step"
        // or that the movement logic here is simplified to an instant jump to the next point in the list.
        this.posicionActual = new Point(proximoPunto.x, proximoPunto.y);

        // 4. Consume fuel based on actual distance moved in this step.
        double distanciaEstePaso = calcularDistanciaManhattan(posicionAnterior, this.posicionActual);
        double consumoCalculadoEstePaso = distanciaEstePaso * plantilla.getConsumoCombustiblePorKm();
        this.combustibleActual -= consumoCalculadoEstePaso;

        if (this.combustibleActual < 0) {
            this.combustibleActual = 0;
            // Consequence of running out of fuel (e.g. status = OUT_OF_SERVICE) is handled by the planner.
        }

        // 5. Advance the index for the next call.
        this.proximoPuntoRutaIndex++;
        
        // After this method, the truck is at 'posicionActual'.
        // The planner (e.g., ACOPlanner in its stepOneMinute) will then:
        // - Check if 'estaEnDestinoDeRuta()' is true.
        // - Check if 'posicionActual' matches a delivery point, refueling station, or depot.
        // - Update truck status (DELIVERING, REFUELING, IDLE, AVAILABLE) accordingly.
        // - Trigger actions like \'realizarEntrega\', \'recargarCombustibleAlMaximo\'.
        // - Assign a new route if necessary.
    }

    /**
     * Indica que el camión ha finalizado o abortado su tarea de recarga.
     * Limpia el tanque de destino para recarga.
     * El estado del camión (ej. AVAILABLE, IDLE) debe ser gestionado por el planificador
     * después de llamar a este método, según corresponda.
     */
    public void finalizarOAbortarRecarga() {
        this.tanqueDestinoRecarga = null;
        // El status se actualiza externamente, por ejemplo, a IDLE o AVAILABLE.
    }

    /**
     * Indica que el camión ha finalizado o abortado su ruta actual.
     * Limpia la ruta actual, resetea el índice del próximo punto y los pedidos asignados a esta ruta.
     * El estado del camión (ej. IDLE, AVAILABLE) debe ser gestionado por el planificador
     * después de llamar a este método. Si está en el depósito, podría ser IDLE, si no, AVAILABLE.
     */
    public void finalizarOAbortarRutaActual() {
        this.rutaActual.clear();
        this.proximoPuntoRutaIndex = -1;
        this.pedidosAsignadosEnRutaActual.clear();
        // El status se actualiza externamente.
        // Por ejemplo, si está en el depósito: this.status = TruckStatus.IDLE;
        // Si está en otro lado: this.status = TruckStatus.AVAILABLE;
        // this.tiempoOcupadoHasta también se gestionaría externamente.
    }

    public void asignarNuevaRuta(List<Point> nuevaRuta, List<Pedido> pedidosEnRuta, TanqueDinamico tanqueRecarga, int tiempoActual) {
        Objects.requireNonNull(nuevaRuta, "La nueva ruta no puede ser nula.");
        this.rutaActual = new ArrayList<>(nuevaRuta); 
        this.proximoPuntoRutaIndex = nuevaRuta.isEmpty() ? -1 : 0;
        this.pedidosAsignadosEnRutaActual = (pedidosEnRuta != null) ? new ArrayList<>(pedidosEnRuta) : new ArrayList<>();
        this.tanqueDestinoRecarga = tanqueRecarga;

        if (nuevaRuta.isEmpty()) {
            this.status = (posicionActual.x == plantilla.getInitialX() && posicionActual.y == plantilla.getInitialY()) ? TruckStatus.IDLE : TruckStatus.AVAILABLE;
            this.tiempoOcupadoHasta = tiempoActual; // Free now
            return;
        }

        // Determine status based on route type
        if (tanqueRecarga != null) {
            this.status = TruckStatus.RETURNING; // Returning to a tank
        } else if (!pedidosAsignadosEnRutaActual.isEmpty()) {
            this.status = TruckStatus.MOVING_TO_DELIVER;
        } else { // Path to depot or other non-delivery, non-refuel point
            boolean toDepot = nuevaRuta.get(nuevaRuta.size()-1).equals(new Point(plantilla.getInitialX(), plantilla.getInitialY()));
            this.status = toDepot ? TruckStatus.RETURNING : TruckStatus.IN_TRANSIT;
        }
        
        double distanciaTotal = 0;
        Point puntoPrev = posicionActual;
        for(Point punto : nuevaRuta) {
            distanciaTotal += calcularDistanciaManhattan(puntoPrev, punto);
            puntoPrev = punto;
        }
        int tiempoEstimadoViaje = 0;
        if (plantilla.getVelocidadPromedioKmPorMin() > 0) {
            tiempoEstimadoViaje = (int) Math.ceil(distanciaTotal / plantilla.getVelocidadPromedioKmPorMin());
        }
        this.tiempoOcupadoHasta = tiempoActual + tiempoEstimadoViaje; 

        // Add time for deliveries/refueling if those are part of this route assignment
        if (status == TruckStatus.MOVING_TO_DELIVER) {
            this.tiempoOcupadoHasta += pedidosAsignadosEnRutaActual.size() * 15; // 15 min per delivery
        }
        if (status == TruckStatus.RETURNING && tanqueDestinoRecarga != null) {
            this.tiempoOcupadoHasta += 10; // 10 min for refueling
        }
    }

    public void recargarCombustibleAlMaximo(TanqueDinamico tanque) { // Called when AT the tank, by ACO or simulation step
        if (tanque != null && posicionActual.equals(new Point(tanque.getPosX(), tanque.getPosY()))) {
            // This method is now more of a trigger; actual refueling time/logic handled by avanzarPasoEnRutaActual or time progression
            // For immediate effect if called outside simulation loop:
            double cantidadNecesaria = plantilla.getCapacidadCombustible() - combustibleActual;
            double cantidadARecargar = Math.min(cantidadNecesaria, tanque.getDisponible());
            this.combustibleActual += cantidadARecargar;
            tanque.dispensarCombustible(cantidadARecargar); 
            this.tanqueDestinoRecarga = null;
            this.status = TruckStatus.AVAILABLE; // Or IDLE if it stays at tank
        } 
    }

    public void recargarCombustibleAlMaximo() { // Generic, e.g., at depot
        this.combustibleActual = plantilla.getCapacidadCombustible();
        this.tanqueDestinoRecarga = null;
        this.status = TruckStatus.IDLE; 
    }

    public boolean intentarCargarPedido(Pedido pedido, int tiempoActual) { // Typically at depot or pickup point
        Objects.requireNonNull(pedido, "El pedido no puede ser nulo.");
        if (pedido.isAtendido() || pedido.isProgramado()) return false;

        if (getCapacidadVolumetricaDisponible() >= pedido.getVolumen()) {
            // Assuming truck is at the correct loading location (e.g., depot for pre-loading)
            this.status = TruckStatus.LOADING;
            this.tiempoOcupadoHasta = tiempoActual + 5; // 5 min for loading one pedido
            pedidosCargados.add(pedido);
            cargaActualVolumen += pedido.getVolumen();
            pedido.setProgramado(true); // Marked as loaded/assigned
            System.out.printf("📦 Camión %s cargó Pedido %d. Estado: LOADING (hasta t+%d).%n", plantilla.getId(), pedido.getId(), this.tiempoOcupadoHasta);
            return true;
        }
        return false;
    }

    public void realizarEntrega(Pedido pedido, int tiempoActual) { // Called when AT delivery point and unloading time is up
        Objects.requireNonNull(pedido, "El pedido no puede ser nulo.");
        if (pedidosCargados.contains(pedido) && posicionActual.equals(new Point(pedido.getX(), pedido.getY()))) {
            pedidosCargados.remove(pedido);
            if (!pedidosAsignadosEnRutaActual.isEmpty() && pedidosAsignadosEnRutaActual.get(0).equals(pedido)) {
                pedidosAsignadosEnRutaActual.remove(0);
            }
            cargaActualVolumen -= pedido.getVolumen();
            pedido.setAtendido(true);
            System.out.printf("✅ Camión %s completó entrega de Pedido %d.%n", plantilla.getId(), pedido.getId());

            // After delivery, what next? Depends on if there are more deliveries on this route.
            if (!pedidosAsignadosEnRutaActual.isEmpty()) {
                this.status = TruckStatus.MOVING_TO_DELIVER; // On to the next delivery
                // tiempoOcupadoHasta for next travel segment will be set by asignarNuevaRuta or by planner adjusting current route
            } else {
                this.status = TruckStatus.AVAILABLE; // No more deliveries on this leg, becomes available for new plan or return to depot
            }
            this.tiempoOcupadoHasta = tiempoActual; // Becomes free for next action immediately after this method
        } else {
             System.err.printf("Error: Intento de procesar entrega para Pedido %d por Camión %s, pero no está cargado o no está en la ubicación.%n", pedido.getId(), plantilla.getId());
        }
    }
    
    public boolean puedeRealizarViaje(Pedido p, double distKm, int tiempoActual) {
        double combustibleNecesario = distKm * plantilla.getConsumoCombustiblePorKm();
        return this.combustibleActual >= combustibleNecesario;
    }

    public void teleportTo(Point newPosition) { // For event processing
        this.posicionActual = new Point(newPosition.x, newPosition.y);
    }

    public CamionEstado deepClone() {
        return new CamionEstado(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CamionEstado that = (CamionEstado) o;
        return Double.compare(that.combustibleActual, combustibleActual) == 0 &&
               Double.compare(that.cargaActualVolumen, cargaActualVolumen) == 0 &&
               proximoPuntoRutaIndex == that.proximoPuntoRutaIndex &&
               tiempoOcupadoHasta == that.tiempoOcupadoHasta &&
               Double.compare(that.consumoTotalRutaProvisional, consumoTotalRutaProvisional) == 0 &&
               Objects.equals(plantilla.getId(), that.plantilla.getId()) && // Compare by ID for template
               Objects.equals(posicionActual, that.posicionActual) &&
               Objects.equals(pedidosCargados, that.pedidosCargados) &&
               Objects.equals(rutaActual, that.rutaActual) &&
               Objects.equals(pedidosAsignadosEnRutaActual, that.pedidosAsignadosEnRutaActual) &&
               Objects.equals(tanqueDestinoRecarga != null ? tanqueDestinoRecarga.getId() : null, 
                              that.tanqueDestinoRecarga != null ? that.tanqueDestinoRecarga.getId() : null) && // Compare by ID
               status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(plantilla.getId(), posicionActual, combustibleActual, cargaActualVolumen, pedidosCargados, 
                          rutaActual, proximoPuntoRutaIndex, pedidosAsignadosEnRutaActual, 
                          (tanqueDestinoRecarga != null ? tanqueDestinoRecarga.getId() : null),
                          status, tiempoOcupadoHasta, consumoTotalRutaProvisional);
    }

    @Override
    public String toString() {
        return "CamionEstado{" +
               "id=" + plantilla.getId() +
               ", status=" + status +
               ", pos=" + posicionActual.x + "," + posicionActual.y +
               ", comb=" + String.format("%.2f", combustibleActual) +
               ", cargaV=" + String.format("%.2f", cargaActualVolumen) +
               ", pedidosCargados=" + pedidosCargados.size() +
               ", rutaActualSz=" + (rutaActual == null ? 0 : rutaActual.size()) +
               ", proxIdx=" + proximoPuntoRutaIndex +
               ", pedidosEnRuta=" + pedidosAsignadosEnRutaActual.size() +
               ", tanqueDest=" + (tanqueDestinoRecarga != null ? tanqueDestinoRecarga.getId() : "N") +
               ", ocupadoHasta=" + tiempoOcupadoHasta +
               '}';
    }
}
