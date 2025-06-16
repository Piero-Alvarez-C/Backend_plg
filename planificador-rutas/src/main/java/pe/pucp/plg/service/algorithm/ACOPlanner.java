package pe.pucp.plg.service.algorithm;

import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.EntregaEvent;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class ACOPlanner {

    private static final int ITERACIONES = 50; 
    private static final int HORMIGAS = 30;    
    private static final double ALPHA = 1.0; // Pheromone influence
    private static final double BETA = 2.0;  // Heuristic influence
    private static final double RHO = 0.1;   // Evaporation rate
    private static final double Q = 100.0;   // Pheromone deposit amount

    public List<Ruta> ejecutarACO(ExecutionContext contexto) {
        List<Pedido> pedidosActivos = contexto.getPedidos().stream()
            .filter(p -> !p.isAtendido() && !p.isDescartado() && !p.isProgramado())
            .collect(Collectors.toList());

        List<CamionEstado> flotaOriginalDisponible = contexto.getCamiones().stream()
            .filter(c -> c.getStatus() == CamionEstado.TruckStatus.AVAILABLE || c.getStatus() == CamionEstado.TruckStatus.IDLE)
            .collect(Collectors.toList());
        
        int tiempoActual = contexto.getCurrentTime();

        int V = flotaOriginalDisponible.size(); 
        int N = pedidosActivos.size(); 
        if (V == 0 || N == 0) return Collections.emptyList();

        Map<Integer, Integer> pedidoIdToIndexMap = new HashMap<>();
        for (int i = 0; i < N; i++) {
            pedidoIdToIndexMap.put(pedidosActivos.get(i).getId(), i);
        }
        
        Map<String, Integer> camionOriginalIdToIndexMap = new HashMap<>();
        for (int i = 0; i < V; i++) {
            camionOriginalIdToIndexMap.put(flotaOriginalDisponible.get(i).getPlantilla().getId(), i);
        }

        double[][] tau = new double[V][N]; 
        for (double[] row : tau) Arrays.fill(row, 1.0);

        List<Ruta> mejorSolucionRutas = null;
        double mejorCosteGlobal = Double.MAX_VALUE;

        for (int it = 0; it < ITERACIONES; it++) {
            List<List<Ruta>> solucionesHormigas = new ArrayList<>();
            for (int h = 0; h < HORMIGAS; h++) {
                List<Integer> pedidosNoAsignadosIndices = new ArrayList<>(); 
                for (int i = 0; i < N; i++) pedidosNoAsignadosIndices.add(i);

                List<CamionEstado> flotaClonadaHormiga = deepCopyFlota(flotaOriginalDisponible);
                List<Ruta> rutasConstruidasHormiga = initRutasParaHormiga(flotaClonadaHormiga);

                while (!pedidosNoAsignadosIndices.isEmpty()) {
                    double[][] probabilidades = calcularProbabilidades(
                        contexto, flotaClonadaHormiga, pedidosActivos, 
                        pedidosNoAsignadosIndices, tau, tiempoActual, camionOriginalIdToIndexMap, pedidoIdToIndexMap);
                    
                    Seleccion sel = muestrearParCamionPedido(probabilidades, pedidosNoAsignadosIndices, flotaClonadaHormiga.size(), pedidosActivos.size());
                    if (sel == null) break; 
                    
                    asignarPedidoACamionClonado(contexto, sel.camionIdx, sel.pedidoIdx, flotaClonadaHormiga, rutasConstruidasHormiga, pedidosActivos, tiempoActual);
                    pedidosNoAsignadosIndices.remove(Integer.valueOf(sel.pedidoIdx));
                }
                solucionesHormigas.add(rutasConstruidasHormiga);
            }

            // Evaporación de feromona
            for (int vIdx = 0; vIdx < V; vIdx++)
                for (int pIdx = 0; pIdx < N; pIdx++)
                    tau[vIdx][pIdx] *= (1 - RHO);

            // Depósito de feromona y búsqueda de la mejor solución de la iteración
            for (List<Ruta> solHormiga : solucionesHormigas) {
                double costeSolHormiga = calcularCosteTotalRutas(solHormiga);
                if (costeSolHormiga < mejorCosteGlobal) {
                    mejorCosteGlobal = costeSolHormiga;
                    mejorSolucionRutas = solHormiga;
                }
                // Depositar feromona
                for (Ruta ruta : solHormiga) { 
                    Integer camionOriginalIdx = camionOriginalIdToIndexMap.get(ruta.getCamionId());
                    if (camionOriginalIdx != null) {
                        for (Integer pedidoId : ruta.getPedidoIds()) { 
                            Integer pedidoActivoIdx = pedidoIdToIndexMap.get(pedidoId);
                            if (pedidoActivoIdx != null) {
                                tau[camionOriginalIdx][pedidoActivoIdx] += Q / (costeSolHormiga + 1e-9); 
                            }
                        }
                    }
                }
            }
        }
        
        if (mejorSolucionRutas != null) {
            return mejorSolucionRutas.stream().filter(r -> !r.getPedidoIds().isEmpty()).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private List<CamionEstado> deepCopyFlota(List<CamionEstado> original) {
        return original.stream().map(CamionEstado::deepClone).collect(Collectors.toList());
    }

    private List<Ruta> initRutasParaHormiga(List<CamionEstado> flotaClonada) {
        List<Ruta> rutas = new ArrayList<>();
        for (CamionEstado camionClonado : flotaClonada) {
            rutas.add(new Ruta(camionClonado.getPlantilla().getId()));
        }
        return rutas;
    }

    private double[][] calcularProbabilidades(
            ExecutionContext contexto,
            List<CamionEstado> flotaClonada, 
            List<Pedido> pedidosActivos,
            List<Integer> indicesPedidosNoAsignados, 
            double[][] tau, 
            int tiempoActual,
            Map<String, Integer> camionOriginalIdToIndexMap,
            Map<Integer, Integer> pedidoIdToActivoIndexMap) {

        int numCamionesClonados = flotaClonada.size();
        int numPedidosActivos = pedidosActivos.size();
        double[][] prob = new double[numCamionesClonados][numPedidosActivos];

        for (int idxCamionClonado = 0; idxCamionClonado < numCamionesClonados; idxCamionClonado++) {
            CamionEstado camionClon = flotaClonada.get(idxCamionClonado);
            Integer idxCamionOriginal = camionOriginalIdToIndexMap.get(camionClon.getPlantilla().getId());
            if (idxCamionOriginal == null) continue; 

            for (int idxPedidoActivo : indicesPedidosNoAsignados) {
                Pedido pedido = pedidosActivos.get(idxPedidoActivo);

                if (camionClon.getCapacidadVolumetricaDisponible() < pedido.getVolumen()) continue; 

                int dx = Math.abs(camionClon.getPosicionActual().x - pedido.getX());
                int dy = Math.abs(camionClon.getPosicionActual().y - pedido.getY());
                int distKm = dx + dy;
                
                double minPorKm = (camionClon.getPlantilla().getVelocidadPromedioKmPorMin() > 0) ? 
                                  (1.0 / camionClon.getPlantilla().getVelocidadPromedioKmPorMin()) : Double.MAX_VALUE;
                if (minPorKm == Double.MAX_VALUE) continue; // Cannot move

                int tiempoViaje = (int) Math.ceil(distKm * minPorKm);
                if (camionClon.getTiempoLibre(tiempoActual) + tiempoViaje > pedido.getTiempoLimite()) continue; 
                if (!camionClon.puedeRealizarViaje(pedido, distKm, tiempoActual)) continue; 
                
                double penalizacionTiempoEspera = 1.0 / (1 + Math.max(0, camionClon.getTiempoLibre(tiempoActual))); 
                double valorHeuristico = (1.0 / (distKm + 1.0)) * penalizacionTiempoEspera; 
                
                prob[idxCamionClonado][idxPedidoActivo] = Math.pow(tau[idxCamionOriginal][idxPedidoActivo], ALPHA) * Math.pow(valorHeuristico, BETA);
            }
        }
        return prob;
    }

    private static class Seleccion { int camionIdx; int pedidoIdx; }

    private Seleccion muestrearParCamionPedido(double[][] probabilidades, List<Integer> indicesPedidosNoAsignados, int numCamiones, int numPedidosActivosTotales) {
        double totalProbabilidad = 0;
        for (int idxCamion = 0; idxCamion < numCamiones; idxCamion++) {
            for (int idxPedido : indicesPedidosNoAsignados) { 
                 if (idxPedido < numPedidosActivosTotales && idxCamion < probabilidades.length && idxPedido < probabilidades[idxCamion].length) { 
                    totalProbabilidad += probabilidades[idxCamion][idxPedido];
                }
            }
        }
        if (totalProbabilidad == 0) return null;

        double randomVal = Math.random() * totalProbabilidad;
        double acumulado = 0;
        for (int idxCamion = 0; idxCamion < numCamiones; idxCamion++) {
            for (int idxPedido : indicesPedidosNoAsignados) {
                if (idxPedido < numPedidosActivosTotales && idxCamion < probabilidades.length && idxPedido < probabilidades[idxCamion].length) { 
                    acumulado += probabilidades[idxCamion][idxPedido];
                    if (acumulado >= randomVal) {
                        Seleccion s = new Seleccion();
                        s.camionIdx = idxCamion; 
                        s.pedidoIdx = idxPedido; 
                        return s;
                    }
                }
            }
        }
        
        if (!indicesPedidosNoAsignados.isEmpty()) {
             Seleccion s = new Seleccion();
             s.camionIdx = new Random().nextInt(numCamiones); // Random truck if roulette fails
             s.pedidoIdx = indicesPedidosNoAsignados.get(0);
             return s;
        }
        return null;
    }

    private boolean asignarPedidoACamionClonado(
            ExecutionContext contexto,
            int idxCamionClonado, 
            int idxPedidoActivo,  
            List<CamionEstado> flotaClonada,
            List<Ruta> rutasConstruidasHormiga, 
            List<Pedido> pedidosActivos,
            int tiempoActual) {

        Ruta rutaCamionClonado = rutasConstruidasHormiga.get(idxCamionClonado);
        CamionEstado camionClon = flotaClonada.get(idxCamionClonado);
        Pedido pedido = pedidosActivos.get(idxPedidoActivo);

        if (rutaCamionClonado.getPedidoIds().contains(pedido.getId())) return false; 
        if (camionClon.getCapacidadVolumetricaDisponible() < pedido.getVolumen()) return false;

        int dx = Math.abs(camionClon.getPosicionActual().x - pedido.getX());
        int dy = Math.abs(camionClon.getPosicionActual().y - pedido.getY());
        int distKm  = dx + dy;
        
        double minPorKm = (camionClon.getPlantilla().getVelocidadPromedioKmPorMin() > 0) ? 
                          (1.0 / camionClon.getPlantilla().getVelocidadPromedioKmPorMin()) : Double.MAX_VALUE;
        if (minPorKm == Double.MAX_VALUE) return false; // Cannot move
        int tiempoViaje = (int) Math.ceil(distKm * minPorKm);

        if (camionClon.getTiempoLibre(tiempoActual) + tiempoViaje > pedido.getTiempoLimite()) return false;
        if (!camionClon.puedeRealizarViaje(pedido, distKm, tiempoActual)) return false;

        // Actualizar estado del Camion Clonado
        camionClon.setTiempoLibre(tiempoActual + camionClon.getTiempoLibre(tiempoActual) + tiempoViaje); 
        camionClon.setPosicionActual(new Point(pedido.getX(), pedido.getY()));
        camionClon.registrarCargaPedido(pedido); 

        // Actualizar la Ruta del Camion Clonado
        rutaCamionClonado.distancia += distKm;
        rutaCamionClonado.consumo += distKm * camionClon.getPlantilla().getConsumoCombustiblePorKm(); // Simplified consumption for this leg
        rutaCamionClonado.addPedidoId(pedido.getId());
        return true;
    }

    private double calcularCosteTotalRutas(List<Ruta> solucionRutas) { 
        return solucionRutas.stream().mapToDouble(r -> r.consumo).sum();
    }

    public CamionEstado findCamion(ExecutionContext contexto, String id) {
        return contexto.getCamiones().stream()
                .filter(c -> c.getPlantilla().getId().equals(id))
                .findFirst().orElse(null);
    }

    public List<Point> buildManhattanPath(ExecutionContext contexto, int x1, int y1, int x2, int y2, int tiempoInicial) {
        List<Point> path = new ArrayList<>();
        Point current = new Point(x1, y1);
        int t = tiempoInicial;
        
        double stepTime = 1.0; // Default time per step (1km)
        if (!contexto.getCamiones().isEmpty() && contexto.getCamiones().get(0).getPlantilla().getVelocidadPromedioKmPorMin() > 0) {
            stepTime = 1.0 / contexto.getCamiones().get(0).getPlantilla().getVelocidadPromedioKmPorMin();
        }

        while (current.x != x2 || current.y != y2) {
            Point prev = new Point(current.x, current.y);
            if      (current.x < x2) current.x++;
            else if (current.x > x2) current.x--;
            else if (current.y < y2) current.y++;
            else                      current.y--;

            Point next = new Point(current.x, current.y);
            int tiempoLlegada = t + (int)Math.ceil(stepTime); 

            if (isBlockedMove(contexto, prev, next, tiempoLlegada)) { 
                List<Point> alt = findPathAStar(contexto, prev.x, prev.y, x2, y2, tiempoLlegada, stepTime);
                if (alt == null || alt.isEmpty()) { 
                    return Collections.emptyList(); 
                }
                path.addAll(alt);
                return path; 
            }
            path.add(next);
            t = tiempoLlegada;
        }
        return path;
    }

    private boolean isBlockedMove(ExecutionContext contexto, Point prev, Point next, int timeMin) {
        for (Bloqueo b : contexto.getBloqueos()) { 
            if (!b.isActiveAt(timeMin)) continue;
            if (b.estaBloqueado(timeMin, next)) { 
                return true;
            }
        }
        return false;
    }
    private static class Node implements Comparable<Node> {
        Point pt; int gCost; double fCost; Node parent; int timeAtNode; 
        Node(Point pt, int g, double f, Node p, int time) { this.pt = pt; this.gCost = g; this.fCost = f; this.parent = p; this.timeAtNode = time; }
        public int compareTo(Node o) { return Double.compare(this.fCost, o.fCost); }
    }

    public List<Point> findPathAStar(ExecutionContext contexto, int x1, int y1, int x2, int y2, int tiempoInicial, double timePerStep) {
        int maxX = 70; int maxY = 50; // Default grid size, consider from context if variable
        if (contexto.getCamiones() != null && !contexto.getCamiones().isEmpty()) { // Try to get from context if possible
            // Assuming grid size might be related to depot or map boundaries in context
        }

        boolean[][] closed = new boolean[maxX + 1][maxY + 1]; 
        PriorityQueue<Node> open = new PriorityQueue<>();
        open.add(new Node(new Point(x1,y1), 0, manhattan(x1,y1,x2,y2), null, tiempoInicial));

        while (!open.isEmpty()) {
            Node curr = open.poll();
            int cx = curr.pt.x, cy = curr.pt.y;
            if (cx == x2 && cy == y2) {
                List<Point> ruta = new ArrayList<>();
                for (Node n = curr; n != null; n = n.parent) ruta.add(n.pt);
                Collections.reverse(ruta);
                if (!ruta.isEmpty()) ruta.remove(0); 
                return ruta;
            }
            if (cx < 0 || cx > maxX || cy < 0 || cy > maxY || closed[cx][cy]) continue;
            closed[cx][cy] = true;

            for (int[] d : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) {
                int nx = cx + d[0], ny = cy + d[1];
                if (nx < 0 || nx > maxX || ny < 0 || ny > maxY) continue;
                Point nextPoint = new Point(nx, ny);

                int timeAtNext = curr.timeAtNode + (int)Math.ceil(timePerStep); 

                boolean blocked = false;
                for (Bloqueo b : contexto.getBloqueos()) { 
                    if (b.estaBloqueado(timeAtNext, nextPoint)) {
                        blocked = true; break;
                    }
                }
                if (blocked && !(nx == x2 && ny == y2)) continue; 
                // if (closed[nx][ny]) continue; // Already checked with outer condition

                int g2 = curr.gCost + 1; 
                double f2 = g2 + manhattan(nx, ny, x2, y2);
                open.add(new Node(nextPoint, g2, f2, curr, timeAtNext));
            }
        }
        return null; 
    }

    private int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    public int stepOneMinute(ExecutionContext contexto) {
        int nuevoTiempo = contexto.getCurrentTime() + 1;
        contexto.setCurrentTime(nuevoTiempo);
        int tiempoActual = nuevoTiempo;
        boolean replanificar = false;

        // 0. Rellenar tanques si es medianoche
        if (tiempoActual > 0 && tiempoActual % 1440 == 0) {
            for (TanqueDinamico tq : contexto.getTanques()) {
                tq.rellenarAlMaximo();
            }
            System.out.printf("🔁 t+%d: Tanques recargados.%n", tiempoActual);
        }

        // 1. Procesar eventos de entrega (teletransportes y actualizaciones de estado)
        Iterator<EntregaEvent> itEv = contexto.getEventosEntrega().iterator();
        while (itEv.hasNext()) {
            EntregaEvent ev = itEv.next();
            if (ev.time == tiempoActual) {
                System.out.println("▶▶▶ Disparando eventoEntrega para Pedido " + ev.getPedido().getId());
                CamionEstado camionEvento = findCamion(contexto, ev.getCamionId());

                if (camionEvento == null) {
                    System.err.printf("Error: Camión %s para evento no encontrado.%n", ev.getCamionId());
                    itEv.remove();
                    continue;
                }
                
                // Teleport and set initial state for delivery
                camionEvento.teleportTo(new Point(ev.getPedido().getX(), ev.getPedido().getY()));
                camionEvento.setStatus(CamionEstado.TruckStatus.DELIVERING);
                camionEvento.setTiempoLibre(tiempoActual + 15); // Standard 15 min unloading time

                // Ensure the pedido is associated with the truck for this delivery event
                // This might involve clearing old route data and setting this as the primary task.
                camionEvento.getPedidosAsignadosEnRutaActual().clear();
                camionEvento.getPedidosAsignadosEnRutaActual().add(ev.getPedido());
                // If the truck wasn't already carrying this specific pedido (e.g. magical event),
                // it might need to be added to pedidosCargados if volume allows, or logic adjusted.
                // For now, assume this event means the truck is now at the location and dedicated to this delivery.

                System.out.printf(
                        "🚚 t+%d: Evento - Camión %s en Pedido #%d. Estado: DELIVERING (hasta t+%d).%n",
                        tiempoActual, camionEvento.getPlantilla().getId(), ev.getPedido().getId(), camionEvento.getTiempoLibre(0)
                );
                itEv.remove();
            }
        }
        
        // 2. Avanzar camiones y actualizar estados post-movimiento
        for (CamionEstado c : contexto.getCamiones()) {
            // A. Guardar posición y estado ANTES de mover
            Point posAntes = new Point(c.getPosicionActual().x, c.getPosicionActual().y);
            CamionEstado.TruckStatus statusAntes = c.getStatus();

            // 1. Check if truck is busy from a previous timed action.
            if (c.getTiempoLibre(tiempoActual) > 0) {
                if (c.getStatus() != CamionEstado.TruckStatus.IDLE) { // Log if busy and not just IDLE (IDLE shouldn't have tiempoLibre > 0 unless error)
                     System.out.printf("⏳ t+%d: Camión %s @(%d,%d). Status: %s. OcupadoHasta: t+%d. Comb: %.2f.%n",
                                      tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y,
                                      c.getStatus(), c.getTiempoLibre(0), c.getCombustibleActual());
                 }
                continue; 
            }

            // At this point, c.getTiempoLibre(tiempoActual) == 0.
            // Truck is free from a previous timed action or was already free.

            boolean accionProcesadaEsteTick = false; 

            // 2.a. Handle completion of timed actions (DELIVERING, REFUELING, LOADING)
            if (statusAntes == CamionEstado.TruckStatus.DELIVERING) {
                Pedido pedidoEntregado = (!c.getPedidosAsignadosEnRutaActual().isEmpty()) ? c.getPedidosAsignadosEnRutaActual().get(0) : null;
                if (pedidoEntregado != null && c.getPosicionActual().equals(new Point(pedidoEntregado.getX(), pedidoEntregado.getY()))) {
                    c.realizarEntrega(pedidoEntregado, tiempoActual); // This updates status, carga, pedido.atendido, and tiempoOcupadoHasta
                    System.out.printf("✅ t+%d: Camión %s completó entrega Pedido #%d @(%d,%d). Nuevo Status: %s. Comb: %.2f.%n",
                                      tiempoActual, c.getPlantilla().getId(), pedidoEntregado.getId(), 
                                      c.getPosicionActual().x, c.getPosicionActual().y, c.getStatus(), c.getCombustibleActual());
                    replanificar = true; 
                } else {
                    System.err.printf("⚠️ t+%d: Camión %s terminó DELIVERING pero no coincide con pedido/lugar esperado. Pos: (%d,%d), Pedido: %s. Estado -> AVAILABLE.%n", 
                                      tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y,
                                      (pedidoEntregado != null ? pedidoEntregado.getId() : "N/A"));
                    c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                    c.finalizarOAbortarRutaActual(); // Clear route if any confusion
                    replanificar = true;
                }
                accionProcesadaEsteTick = true;
            } else if (statusAntes == CamionEstado.TruckStatus.REFUELING) {
                TanqueDinamico tanqueRecargaOriginal = c.getTanqueDestinoRecarga(); // Check based on where it was supposed to be
                Point posActual = c.getPosicionActual();
                boolean enDeposito = posActual.equals(new Point(contexto.getDepositoX(), contexto.getDepositoY()));
                boolean enTanqueDesignado = tanqueRecargaOriginal != null && posActual.equals(new Point(tanqueRecargaOriginal.getPosX(), tanqueRecargaOriginal.getPosY()));

                if (enTanqueDesignado) {
                    c.recargarCombustibleAlMaximo(tanqueRecargaOriginal); 
                    System.out.printf("⛽ t+%d: Camión %s terminó recarga en Tanque %s @(%d,%d). Comb: %.2f.%n",
                                      tiempoActual, c.getPlantilla().getId(), tanqueRecargaOriginal.getId(), posActual.x, posActual.y, c.getCombustibleActual());
                } else if (enDeposito && tanqueRecargaOriginal == null) { // Was refueling at depot
                    c.recargarCombustibleAlMaximo(); 
                     System.out.printf("⛽ t+%d: Camión %s terminó recarga en Depósito @(%d,%d). Comb: %.2f.%n",
                                      tiempoActual, c.getPlantilla().getId(), posActual.x, posActual.y, c.getCombustibleActual());
                } else {
                     System.err.printf("⚠️ t+%d: Camión %s terminó REFUELING pero no en locación esperada (%s). Estado -> AVAILABLE.%n", 
                                       tiempoActual, c.getPlantilla().getId(), 
                                       (tanqueRecargaOriginal != null ? "Tanque " + tanqueRecargaOriginal.getId() : "Depósito"));
                }
                c.finalizarOAbortarRecarga(); // Clears tanqueDestinoRecarga
                c.setStatus(CamionEstado.TruckStatus.IDLE); 
                replanificar = true;
                accionProcesadaEsteTick = true;
            } else if (statusAntes == CamionEstado.TruckStatus.LOADING) {
                System.out.printf("📦 t+%d: Camión %s terminó LOADING @(%d,%d).%n",
                                  tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y);
                c.setStatus(CamionEstado.TruckStatus.IDLE); 
                replanificar = true;
                accionProcesadaEsteTick = true;
            }

            // 2.b. If no timed action was completed, check if truck arrived at the destination of its current route.
            if (!accionProcesadaEsteTick && c.tieneRutaAsignada() && c.estaEnDestinoDeRuta()) {
                Point destinoActual = c.getPosicionActual(); // Should be the last point of rutaActual

                Pedido primerPedidoEnRuta = (!c.getPedidosAsignadosEnRutaActual().isEmpty()) ? c.getPedidosAsignadosEnRutaActual().get(0) : null;
                boolean esDestinoEntrega = primerPedidoEnRuta != null && destinoActual.equals(new Point(primerPedidoEnRuta.getX(), primerPedidoEnRuta.getY()));
                boolean esDestinoRecargaTanque = c.getTanqueDestinoRecarga() != null && destinoActual.equals(new Point(c.getTanqueDestinoRecarga().getPosX(), c.getTanqueDestinoRecarga().getPosY()));
                boolean esDestinoDeposito = destinoActual.equals(new Point(contexto.getDepositoX(), contexto.getDepositoY()));

                if (esDestinoEntrega) {
                    System.out.printf("🚚 t+%d: Camión %s llegó a Pedido #%d @(%d,%d). Iniciando entrega.%n",
                                      tiempoActual, c.getPlantilla().getId(), primerPedidoEnRuta.getId(), destinoActual.x, destinoActual.y);
                    c.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    c.setTiempoLibre(tiempoActual + 15); // 15 min para descargar
                } else if (esDestinoRecargaTanque) {
                    System.out.printf("⛽ t+%d: Camión %s llegó a Tanque %s @(%d,%d) para recargar. Iniciando recarga.%n",
                                      tiempoActual, c.getPlantilla().getId(), c.getTanqueDestinoRecarga().getId(), destinoActual.x, destinoActual.y);
                    c.setStatus(CamionEstado.TruckStatus.REFUELING);
                    c.setTiempoLibre(tiempoActual + 10); // 10 min para recargar
                } else if (esDestinoDeposito) {
                    System.out.printf("🏠 t+%d: Camión %s llegó al Depósito @(%d,%d).%n",
                                      tiempoActual, c.getPlantilla().getId(), destinoActual.x, destinoActual.y);
                    c.finalizarOAbortarRutaActual(); 
                    c.setStatus(CamionEstado.TruckStatus.IDLE);
                    if (c.getCombustibleActual() < c.getPlantilla().getCapacidadCombustible() * 0.30) { // Refuel if below 30% at depot
                        System.out.printf("⛽ t+%d: Camión %s recargando automáticamente en Depósito.%n", tiempoActual, c.getPlantilla().getId());
                        c.recargarCombustibleAlMaximo(); 
                    }
                    replanificar = true; 
                } else { // Arrived at some other end-of-route point
                    System.out.printf("📍 t+%d: Camión %s llegó al final de su ruta @(%d,%d) (no es entrega/tanque/depósito). Estado -> AVAILABLE.%n",
                                      tiempoActual, c.getPlantilla().getId(), destinoActual.x, destinoActual.y);
                    c.finalizarOAbortarRutaActual();
                    c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                    replanificar = true;
                }
                accionProcesadaEsteTick = true; // An arrival was processed.
            }
            
            // If an action was processed (completion or arrival initiating new timed action),
            // and that action made the truck busy, skip movement for this tick.
            if (c.getTiempoLibre(tiempoActual) > 0) {
                 // Log if it just became busy, otherwise step 1 would have caught it.
                 if (accionProcesadaEsteTick) { // Only log if it became busy in *this* tick's processing
                    System.out.printf("⏳ t+%d: Camión %s @(%d,%d) ahora %s. OcupadoHasta: t+%d.%n",
                                      tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y,
                                      c.getStatus(), c.getTiempoLibre(0));
                 }
                continue; 
            }


            // 3. If still free, has a route, and not at its destination, then try to move.
            if (c.getTiempoLibre(tiempoActual) == 0 && c.tieneRutaAsignada() && !c.estaEnDestinoDeRuta()) {
                // Check for fuel BEFORE attempting to move.
                if (c.getCombustibleActual() <= 0) {
                    boolean atRefuelDepotB = c.getPosicionActual().equals(new Point(contexto.getDepositoX(), contexto.getDepositoY()));
                    TanqueDinamico currentTankLocationB = null;
                    for (TanqueDinamico td : contexto.getTanques()) {
                        if (c.getPosicionActual().equals(new Point(td.getPosX(), td.getPosY()))) {
                            currentTankLocationB = td;
                            break;
                        }
                    }
                    if (!atRefuelDepotB && currentTankLocationB == null) { // Stranded
                        System.out.printf("⛽❌ t+%d: Camión %s SIN COMBUSTIBLE @(%d,%d) ANTES de mover! Estado -> OUT_OF_SERVICE.%n",
                                          tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y);
                        c.setStatus(CamionEstado.TruckStatus.OUT_OF_SERVICE);
                        c.finalizarOAbortarRutaActual();
                        // c.setTiempoLibre(tiempoActual); 
                        replanificar = true;
                        System.out.printf("🚛 (OUT_OF_SERVICE) t+%d: Camión %s @(%d,%d). Comb: %.2f. Status: %s.%n", // Simplified log for this case
                                          tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y,
                                          c.getCombustibleActual(), c.getStatus());
                        continue; 
                    } else {
                         System.out.printf("⛽⚠️ t+%d: Camión %s sin combustible pero en locación de recarga. Esperando ciclo de recarga.%n",
                                          tiempoActual, c.getPlantilla().getId());
                         // Will be handled by arrival logic (2.b) in next tick if not already REFUELING.
                    }
                } else { // Has fuel, attempt to move
                    if (c.getStatus() == CamionEstado.TruckStatus.IN_TRANSIT ||
                        c.getStatus() == CamionEstado.TruckStatus.MOVING_TO_DELIVER ||
                        c.getStatus() == CamionEstado.TruckStatus.RETURNING) {

                        Point posAntesDeAvanzar = new Point(c.getPosicionActual().x, c.getPosicionActual().y);
                        c.avanzarPasoEnRutaActual(); 

                        if (!c.getPosicionActual().equals(posAntesDeAvanzar)) {
                            System.out.printf("➡️ t+%d: Camión %s movió de (%d,%d) a (%d,%d). RutaIdx: %d/%d. Comb: %.2f. Status: %s.%n",
                                              tiempoActual, c.getPlantilla().getId(), posAntesDeAvanzar.x, posAntesDeAvanzar.y,
                                              c.getPosicionActual().x, c.getPosicionActual().y,
                                              c.getProximoPuntoRutaIndex(), (c.getRutaActual() != null ? c.getRutaActual().size() : 0),
                                              c.getCombustibleActual(), c.getStatus());
                        } else if (c.tieneRutaAsignada() && !c.estaEnDestinoDeRuta()){ 
                             System.out.printf("⚠️ t+%d: Camión %s (%s) en ruta (%d/%d) pero no avanzó de (%d,%d). Comb: %.2f.%n",
                                              tiempoActual, c.getPlantilla().getId(), c.getStatus(),
                                              c.getProximoPuntoRutaIndex(), (c.getRutaActual() != null ? c.getRutaActual().size() : 0),
                                              posAntesDeAvanzar.x, posAntesDeAvanzar.y, c.getCombustibleActual());
                        }

                        // After advancing, check for out-of-fuel immediately.
                        if (c.getCombustibleActual() <= 0 && c.getStatus() != CamionEstado.TruckStatus.OUT_OF_SERVICE) {
                            boolean atRefuelDepotC = c.getPosicionActual().equals(new Point(contexto.getDepositoX(), contexto.getDepositoY()));
                            TanqueDinamico currentTankLocationC = null;
                            for (TanqueDinamico td : contexto.getTanques()) {
                                if (c.getPosicionActual().equals(new Point(td.getPosX(), td.getPosY()))) {
                                    currentTankLocationC = td;
                                    break;
                                }
                            }
                            if (!atRefuelDepotC && currentTankLocationC == null) { // Stranded
                                System.out.printf("⛽❌ t+%d: Camión %s SIN COMBUSTIBLE @(%d,%d) DESPUÉS de mover! Estado -> OUT_OF_SERVICE.%n",
                                                  tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y);
                                c.setStatus(CamionEstado.TruckStatus.OUT_OF_SERVICE);
                                c.finalizarOAbortarRutaActual();
                                replanificar = true;
                                System.out.printf("🚛 (OUT_OF_SERVICE) t+%d: Camión %s @(%d,%d). Comb: %.2f. Status: %s.%n",
                                                  tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y,
                                                  c.getCombustibleActual(), c.getStatus());
                                continue; 
                            } else {
                                 System.out.printf("⛽⚠️ t+%d: Camión %s sin combustible DESPUÉS de mover, pero en locación de recarga. Esperando ciclo de recarga.%n",
                                                  tiempoActual, c.getPlantilla().getId());
                                // Arrival at refuel spot will be handled in next tick's step 2.b
                            }
                        }
                    } else {
                        System.out.printf("⚠️ t+%d: Camión %s (%s) tiene ruta pero status no es de movimiento. No avanzó. Pos: (%d,%d), Ruta: %d/%d%n",
                                          tiempoActual, c.getPlantilla().getId(), c.getStatus(), 
                                          c.getPosicionActual().x, c.getPosicionActual().y,
                                          c.getProximoPuntoRutaIndex(), (c.getRutaActual() != null ? c.getRutaActual().size() : 0));
                    }
                }
            }

            // 4. Identify trucks that are IDLE or AVAILABLE and have no route (candidate for ACO)
            //    or log final state if no other major event/log occurred.
            if (c.getTiempoLibre(tiempoActual) == 0 && 
                (c.getStatus() == CamionEstado.TruckStatus.IDLE || c.getStatus() == CamionEstado.TruckStatus.AVAILABLE) &&
                !c.tieneRutaAsignada()) {
                // This truck is a candidate for ACO planning.
                // replanificar should have been set if it just became available/idle.
                System.out.printf("ℹ️ t+%d: Camión %s @(%d,%d) es %s y sin ruta. Comb: %.2f. Candidato para replanificación.%n",
                                  tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y, 
                                  c.getStatus(), c.getCombustibleActual());
            } else if (c.getTiempoLibre(tiempoActual) == 0 && c.getStatus() != CamionEstado.TruckStatus.OUT_OF_SERVICE && !accionProcesadaEsteTick && c.getPosicionActual().equals(posAntes)) {
                // If no action, no move, not busy, not out of service, log its current state if it wasn't logged by other paths.
                // This catches trucks that are e.g. IN_TRANSIT but stuck due to some condition not yet handled, or IDLE with a route but not moving.
                 System.out.printf("🚛 (No-Op?) t+%d: Camión %s @(%d,%d). Comb: %.2f. Status: %s. RutaPts: %d NextIdx: %d%n",
                                  tiempoActual, c.getPlantilla().getId(), c.getPosicionActual().x, c.getPosicionActual().y,
                                  c.getCombustibleActual(), c.getStatus(),
                                  c.getRutaActual() != null ? c.getRutaActual().size() : 0, c.getProximoPuntoRutaIndex());
            }
        }


        // 3. Procesar nuevos pedidos
        List<Pedido> nuevosPedidosDelMinuto = contexto.getPedidosPorTiempo().remove(tiempoActual);
        if (nuevosPedidosDelMinuto != null && !nuevosPedidosDelMinuto.isEmpty()) {
            double capacidadMaxCamionVol = contexto.getCamiones().stream()
                .mapToDouble(cam -> cam.getPlantilla().getCapacidadCarga()) 
                .max().orElse(1.0);

            List<Pedido> pedidosProcesados = new ArrayList<>();
            for (Pedido p : nuevosPedidosDelMinuto) {
                if (p.getVolumen() <= 0) continue;
                if (capacidadMaxCamionVol > 0 && p.getVolumen() > capacidadMaxCamionVol && contexto.getCamiones().size() > 0) {
                    System.out.printf("Splitting Pedido #%d (vol %.1f) due to capacity limit (%.1f)%n", p.getId(), p.getVolumen(), capacidadMaxCamionVol);
                    double volumenRestante = p.getVolumen();
                    while (volumenRestante > 0) {
                        double volSubPedido = Math.min(capacidadMaxCamionVol, volumenRestante);
                        Pedido sub = new Pedido(contexto.generateUniquePedidoId(), tiempoActual, p.getX(), p.getY(), volSubPedido, p.getTiempoLimite());
                        pedidosProcesados.add(sub);
                        volumenRestante -= volSubPedido;
                    }
                } else {
                    pedidosProcesados.add(p);
                }
            }
            contexto.getPedidos().addAll(pedidosProcesados);
            for (Pedido p : pedidosProcesados) {
                System.out.printf("🆕 t+%d: Pedido #%d recibido (destino=(%d,%d), vol=%.1fm³, límite t+%d)%n",
                        tiempoActual, p.getId(), p.getX(), p.getY(), p.getVolumen(), p.getTiempoLimite());
            }
            replanificar = true; 
        }

        Iterator<Pedido> itP = contexto.getPedidos().iterator();
        while (itP.hasNext()) {
            Pedido p = itP.next();
            if (!p.isAtendido() && !p.isDescartado() && !p.isProgramado() && tiempoActual > p.getTiempoLimite()) {
                System.out.printf("💥 Colapso en t+%d, pedido %d incumplido. Límite: t+%d.%n",
                        tiempoActual, p.getId(), p.getTiempoLimite());
                p.setDescartado(true); 
            }
        }

        boolean camionDisponibleLibre = contexto.getCamiones().stream().anyMatch(c -> 
            (c.getStatus() == CamionEstado.TruckStatus.AVAILABLE || c.getStatus() == CamionEstado.TruckStatus.IDLE) && 
            c.getTiempoLibre(tiempoActual) == 0); // Is actually free now
        
        boolean hayPedidosNoProgramados = contexto.getPedidos().stream().anyMatch(p -> !p.isAtendido() && !p.isDescartado() && !p.isProgramado());

        if (replanificar || (camionDisponibleLibre && hayPedidosNoProgramados) || (tiempoActual > 0 && tiempoActual % 30 == 0 && hayPedidosNoProgramados) ) {
            System.out.printf("🔄 t+%d: Replanificación. Motivos: replanFlag=%b, camionLibreYHayPedidos=%b, periodicoYHayPedidos=%b%n",
                tiempoActual, replanificar, (camionDisponibleLibre && hayPedidosNoProgramados), (tiempoActual > 0 && tiempoActual % 30 == 0 && hayPedidosNoProgramados));
            
            List<Ruta> nuevasRutasACO = ejecutarACO(contexto); 
            if (nuevasRutasACO != null && !nuevasRutasACO.isEmpty()) {
                // Clear only routes for trucks that are getting new assignments or are IDLE/AVAILABLE
                // For now, simple approach: clear all non-active routes and assign new ones.
                // More sophisticated: merge or selectively update.
                contexto.getRutas().removeIf(r -> {
                    CamionEstado c = findCamion(contexto, r.getCamionId());
                    return c == null || c.getStatus() == CamionEstado.TruckStatus.AVAILABLE || c.getStatus() == CamionEstado.TruckStatus.IDLE;
                });
                contexto.getRutas().addAll(nuevasRutasACO);
                System.out.printf("🗺️ t+%d: %d nuevas rutas generadas por ACO.%n", tiempoActual, nuevasRutasACO.size());

                for (Ruta nr : nuevasRutasACO) {
                    CamionEstado camionAsignado = findCamion(contexto, nr.getCamionId());
                    if (camionAsignado != null && (camionAsignado.getStatus() == CamionEstado.TruckStatus.AVAILABLE || camionAsignado.getStatus() == CamionEstado.TruckStatus.IDLE)) {
                        
                        List<Pedido> pedidosParaEstaRuta = nr.getPedidoIds().stream()
                            .map(pedidoId -> contexto.getPedidos().stream().filter(pd -> pd.getId() == pedidoId && !pd.isProgramado() && !pd.isAtendido()).findFirst().orElse(null))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                        
                        if (pedidosParaEstaRuta.isEmpty()) continue; // No valid, unassigned pedidos for this route

                        List<Point> puntosCamino = new ArrayList<>();
                        Point camionCurrentPos = camionAsignado.getPosicionActual();
                        int tiempoAcumuladoRuta = tiempoActual;

                        // Pre-load pedidos if truck is at depot
                        if (camionAsignado.getStatus() == CamionEstado.TruckStatus.IDLE && camionAsignado.getPosicionActual().equals(new Point(contexto.getDepositoX(), contexto.getDepositoY()))) {
                            for (Pedido p : pedidosParaEstaRuta) {
                                if (camionAsignado.getCapacidadVolumetricaDisponible() >= p.getVolumen()) {
                                    // Llamar a intentarCargarPedido que actualiza estado y tiempoOcupadoHasta
                                    if (camionAsignado.intentarCargarPedido(p, tiempoAcumuladoRuta)) {
                                         System.out.printf("📦 t+%d: Camión %s cargando Pedido #%d en depósito. Ocupado hasta t+%d.%n",
                                                          tiempoActual, camionAsignado.getPlantilla().getId(), p.getId(), camionAsignado.getTiempoLibre(0));
                                         tiempoAcumuladoRuta = camionAsignado.getTiempoLibre(0); // Actualizar tiempo base para el siguiente cálculo
                                    } else {
                                         System.out.println("WARN: Falló intento de cargar pedido " + p.getId() + " en camión " + camionAsignado.getPlantilla().getId() + " (ya programado o atendido).");
                                    }
                                } else {
                                    System.out.println("WARN: Cannot load pedido " + p.getId() + " due to capacity on camion " + camionAsignado.getPlantilla().getId());
                                    // This pedido might need to be unassigned from this route or handled
                                }
                            }
                        }

                        for (Pedido pedidoActual : pedidosParaEstaRuta) {
                            List<Point> segmento = buildManhattanPath(contexto, camionCurrentPos.x, camionCurrentPos.y, pedidoActual.getX(), pedidoActual.getY(), tiempoAcumuladoRuta);
                            if (segmento.isEmpty() && !(camionCurrentPos.x == pedidoActual.getX() && camionCurrentPos.y == pedidoActual.getY())) {
                                System.err.printf("Error: No se pudo generar ruta para camión %s a pedido %d%n", camionAsignado.getPlantilla().getId(), pedidoActual.getId());
                                continue; 
                            }
                            puntosCamino.addAll(segmento);
                            camionCurrentPos = new Point(pedidoActual.getX(), pedidoActual.getY()); 
                            pedidoActual.setProgramado(true); 
                            // Estimar tiempo para llegar al punto del pedido
                            if (!segmento.isEmpty() && camionAsignado.getPlantilla().getVelocidadPromedioKmPorMin() > 0) {
                                tiempoAcumuladoRuta += (int)Math.ceil(segmento.size() / camionAsignado.getPlantilla().getVelocidadPromedioKmPorMin());
                            }
                            // Añadir tiempo de descarga para este pedido
                            tiempoAcumuladoRuta += 15; // Standard 15 min delivery time
                        }

                        if (!puntosCamino.isEmpty()) {
                            // Asignar la ruta completa y los pedidos asociados
                            // El tiempoActual para asignarNuevaRuta es el tiempo base de inicio de la ruta.
                            // El tiempoOcupadoHasta del camión se calculará DENTRO de asignarNuevaRuta basado en la ruta y acciones.
                            camionAsignado.asignarNuevaRuta(puntosCamino, pedidosParaEstaRuta, null, tiempoActual); 
                            
                            System.out.printf("  🗺️ Camión %s asignado a ruta con %d pedidos. Próximo: (%d,%d). Status: %s. OcupadoHasta: t+%d%n",
                                camionAsignado.getPlantilla().getId(), pedidosParaEstaRuta.size(), 
                                camionAsignado.getRutaActual().get(0).x, camionAsignado.getRutaActual().get(0).y, 
                                camionAsignado.getStatus(), camionAsignado.getTiempoLibre(0)); // tiempoOcupadoHasta
                        } else if (!pedidosParaEstaRuta.isEmpty() && camionAsignado.getPedidosCargados().containsAll(pedidosParaEstaRuta)) {
                            // Caso especial: todos los pedidos ya están cargados y en la ubicación actual (depósito)
                            // Se crea una "ruta" que es solo el punto actual, para manejar las entregas.
                            camionAsignado.asignarNuevaRuta(Collections.singletonList(camionAsignado.getPosicionActual()), pedidosParaEstaRuta, null, tiempoActual);
                             System.out.printf("  🗺️ Camión %s tiene %d pedidos cargados en sitio. Status: %s. OcupadoHasta: t+%d%n",
                                camionAsignado.getPlantilla().getId(), pedidosParaEstaRuta.size(),
                                camionAsignado.getStatus(), camionAsignado.getTiempoLibre(0));
                        }
                    }
                }
            } else {
                 System.out.printf("ℹ️ t+%d: ACO no generó nuevas rutas.%n", tiempoActual);
            }
        }
        return tiempoActual; 
    }
}