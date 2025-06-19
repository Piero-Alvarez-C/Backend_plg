package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.dto.SimulationRequest;
import pe.pucp.plg.dto.SimulationStatusDTO;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.EntregaEvent;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;
import pe.pucp.plg.service.algorithm.ACOPlanner;
import pe.pucp.plg.util.ParseadorArchivos;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SimulacionService {

    private final SimulationManagerService simulationManagerService;
    private final ACOPlanner acoPlanner;
    private final ArchivoService archivoService;

    // Helper classes needed for A* pathfinding, defined internally to keep service self-contained.
    private static class Point {
        int x, y;
        public Point(int x, int y) { this.x = x; this.y = y; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Point point = (Point) o;
            return x == point.x && y == point.y;
        }
        @Override
        public int hashCode() { return Objects.hash(x, y); }
        public int manhattanDistance(Point other) {
            return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
        }
    }

    private static class Node {
        Point position;
        Node parent;
        int gCost; // Cost from start to current node
        int hCost; // Heuristic cost from current node to end
        int fCost; // Total cost (gCost + hCost)

        Node(Point position) {
            this.position = position;
            this.gCost = Integer.MAX_VALUE;
        }

        Node(Point position, Node parent, int gCost, int hCost) {
            this.position = position;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
            this.fCost = gCost + hCost;
        }
    }

    @Autowired
    public SimulacionService(SimulationManagerService simulationManagerService,
                             ArchivoService archivoService) {
        this.simulationManagerService = simulationManagerService;
        this.acoPlanner = new ACOPlanner(); 
        this.archivoService = archivoService;
    }

    /**
     * Initiates a new simulation instance using the SimulationManagerService.
     * The manager creates a fresh, initialized ExecutionContext.
     * This version is for resetting the operational context or creating a simple simulation.
     * @return The ID of the newly created simulation context.
     */
    public String iniciarSimulacion() {
        // This method now primarily serves to initialize/reset the operational context
        // or create a basic simulation context if needed elsewhere without file inputs.
        // For simulations based on file inputs, use iniciarSimulacion(SimulationRequest request).
        simulationManagerService.initializeOperationalContext(); // Or a more specific reset method if available
        // If it's about creating a generic new simulation context ID:
        // return simulationManagerService.crearContextoSimulacion(); 
        return "operational_context_reset_or_initialized"; // Placeholder, adjust as per exact need
    }

    /**
     * Advances the simulation identified by simulationId by one minute.
     * @param simulationId The ID of the simulation to step forward.
     * @return The new current time of the simulation.
     */
    public int stepOneMinute(String simulationId) {
        // Obtener el contexto de ejecución
        ExecutionContext contexto = simulationManagerService.getContextoSimulacion(simulationId);
        if (contexto == null) {
            if ("operational".equals(simulationId)) {
                contexto = simulationManagerService.getOperationalContext();
            } else {
                throw new IllegalArgumentException("Simulation context not found for ID: " + simulationId);
            }
        }

        // 1. AVANZAR EL TIEMPO
        int tiempoActual = contexto.getCurrentTime() + 1;
        contexto.setCurrentTime(tiempoActual);
        boolean replanificar = (tiempoActual == 0); // Replanificar al inicio siempre
        
        // 2. PROCESAR EVENTOS GLOBALES
        
        // 2.1 Recargar tanques (cada 24 horas)
        if (tiempoActual > 0 && tiempoActual % 1440 == 0) {
            System.out.println("⛽ Recarga diaria de tanques en t+" + tiempoActual);
            for (TanqueDinamico tq : contexto.getTanques()) {
                tq.setDisponible(tq.getCapacidadTotal());
            }
        }
        
        // 2.2 Procesar eventos de entrega programados
        procesarEventosEntrega(contexto, tiempoActual);
        
        // 2.3 Incorporar nuevos pedidos que llegan en este tiempo
        List<Pedido> nuevos = contexto.getPedidosPorTiempo().remove(tiempoActual);
        if (nuevos != null && !nuevos.isEmpty()) {
            System.out.printf("📦 %d nuevos pedidos recibidos en t+%d%n", nuevos.size(), tiempoActual);
            contexto.getPedidos().addAll(nuevos);
            replanificar = true;
        }
        
        // 2.4 Comprobar pedidos caducados (fuera de tiempo límite)
        Iterator<Pedido> itP = contexto.getPedidos().iterator();
        while (itP.hasNext()) {
            Pedido p = itP.next();
            if (!p.isAtendido() && !p.isDescartado() && tiempoActual > p.getTiempoLimite()) {
                p.setDescartado(true);
                System.out.printf("⚠️ Pedido %d descartado por tiempo límite en t+%d%n", p.getId(), tiempoActual);
                itP.remove();
                // No necesitamos replanificar aquí, pues eliminar un pedido caducado no afecta planes futuros
            }
        }
        
        // 2.5 Procesar averías por turno
        replanificar |= procesarAverias(contexto, tiempoActual);
        
        // 3. MOVER CAMIONES Y PROCESAR LLEGADAS
        
        // 3.1 Avanzar cada camión en sus rutas actuales
        for (CamionEstado c : contexto.getCamiones()) {
            if (c.tienePasosPendientes()) {
                c.avanzarUnPaso(); // El camión se mueve según su ruta actual
            } 
        }
        
        // 3.2 Procesar las llegadas de camiones a sus destinos
        for (CamionEstado c : contexto.getCamiones()) {
            if (!c.tienePasosPendientes() && c.getStatus() == CamionEstado.TruckStatus.RETURNING) {
                // Camión ha llegado al final de su ruta de retorno
                c.setCapacidadDisponible(c.getPlantilla().getCapacidadCarga());
                c.setCombustibleDisponible(c.getPlantilla().getCapacidadCombustible());
                c.setEnRetorno(false);
                c.setReabastecerEnTanque(null);
                c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                c.setTiempoLibre(tiempoActual + 15); // Tiempo de descarga/recarga
                
                System.out.printf("🚚 Camión %s ha completado su retorno y está disponible en t+%d%n", 
                                 c.getPlantilla().getId(), tiempoActual + 15);
            }
        }
        
        // 4. REPLANIFICACIÓN SI ES NECESARIO
        
        // Si hay nuevos pedidos, averías resueltas, o es inicio de simulación
        if (replanificar) {
            System.out.println("🔄 Ejecutando replanificación en t+" + tiempoActual);
            
            // 4.1 Solicitar al ACOPlanner que calcule nuevas rutas óptimas
            List<Ruta> nuevasRutas = acoPlanner.planificarRutas(contexto);
            
            // 4.2 Traducir el plan a acciones concretas sobre el estado real
            aplicarNuevasRutas(tiempoActual, nuevasRutas, contexto);
            
            // 4.3 Guardar las rutas en el contexto para visualización o análisis
            contexto.setRutas(nuevasRutas);
        }
        
        return contexto.getCurrentTime();
    }
    
    /**
     * Procesa los eventos de entrega que ocurren en el tiempo actual.
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     */
    private void procesarEventosEntrega(ExecutionContext contexto, int tiempoActual) {
        Iterator<EntregaEvent> itEv = contexto.getEventosEntrega().iterator();
        while (itEv.hasNext()) {
            EntregaEvent ev = itEv.next();
            if (ev.time == tiempoActual) {
                CamionEstado camion = findCamion(ev.getCamionId(), contexto);
                if (camion != null) {
                    // Actualizar posición y estado del camión
                    camion.setX(ev.getPedido().getX());
                    camion.setY(ev.getPedido().getY());
                    camion.setTiempoLibre(tiempoActual + 15); // 15 minutos para descargar
                    
                    // Actualizar capacidad disponible
                    double dispAntes = camion.getCapacidadDisponible();
                    if (dispAntes >= ev.getPedido().getVolumen()) {
                        camion.setCapacidadDisponible(dispAntes - ev.getPedido().getVolumen());
                    }
                    
                    // Marcar el pedido como atendido
                    ev.getPedido().setAtendido(true);
                    
                    System.out.printf("✅ Entrega realizada - Pedido %d por camión %s en t+%d%n", 
                                     ev.getPedido().getId(), camion.getPlantilla().getId(), tiempoActual);
                    
                    // Eliminar el evento procesado
                    itEv.remove();
                }
            }
        }
    }
    
    /**
     * Procesa las averías según el turno actual.
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     * @return true si ocurrieron cambios que requieren replanificación
     */
    private boolean procesarAverias(ExecutionContext contexto, int tiempoActual) {
        boolean replanificar = false;
        
        // Determinar el turno actual
        String turnoActual = turnoDeMinuto(tiempoActual);
        
        // Si cambió el turno, limpiar estados de averías anteriores
        if (!turnoActual.equals(contexto.getTurnoAnterior())) {
            contexto.setTurnoAnterior(turnoActual);
            contexto.getAveriasAplicadas().clear();
            contexto.getCamionesInhabilitados().clear();
        }
        
        // Aplicar averías programadas para este turno
        Map<String, String> averiasTurno = contexto.getAveriasPorTurno().getOrDefault(turnoActual, Collections.emptyMap());
        for (Map.Entry<String, String> entry : averiasTurno.entrySet()) {
            String key = turnoActual + "_" + entry.getKey();
            if (contexto.getAveriasAplicadas().contains(key)) continue;
            
            CamionEstado c = findCamion(entry.getKey(), contexto);
            if (c != null && c.getTiempoLibre() <= tiempoActual) {
                // Determinar penalización según tipo de avería
                int penal = entry.getValue().equals("T1") ? 30 : 
                           entry.getValue().equals("T2") ? 60 : 90;
                           
                c.setTiempoLibre(tiempoActual + penal);
                contexto.getAveriasAplicadas().add(key);
                contexto.getCamionesInhabilitados().add(c.getPlantilla().getId());
                
                System.out.printf("🔧 Avería tipo %s en camión %s - Inhabilitado hasta t+%d%n", 
                                 entry.getValue(), c.getPlantilla().getId(), tiempoActual + penal);
                                 
                replanificar = true;
            }
        }
        
        // Revisar camiones que ya pueden volver a servicio
        Iterator<String> it = contexto.getCamionesInhabilitados().iterator();
        while (it.hasNext()) {
            CamionEstado c = findCamion(it.next(), contexto);
            if (c != null && c.getTiempoLibre() <= tiempoActual) {
                it.remove();
                System.out.printf("🚚 Camión %s reparado y disponible nuevamente en t+%d%n", 
                                 c.getPlantilla().getId(), tiempoActual);
                replanificar = true;
            }
        }
        
        return replanificar;
    }

    /**
     * Gets the current time of a specific simulation.
     * @param simulationId The ID of the simulation.
     * @return The current time.
     */
    public int getTiempoActual(String simulationId) {
        ExecutionContext currentContext = simulationManagerService.getContextoSimulacion(simulationId);
        if (currentContext == null) {
            throw new IllegalArgumentException("Simulation context not found for ID: " + simulationId);
        }
        return currentContext.getCurrentTime();
    }

    /**
     * Initiates a new simulation based on input files specified in the request.
     * @param request The request containing file IDs and simulation name.
     * @return A DTO with the status and ID of the new simulation.
     */
    public SimulationStatusDTO iniciarSimulacion(SimulationRequest request) {
        try {
            // 1. Crear el contexto. Esta parte está bien.
            String simulationId = simulationManagerService.crearContextoSimulacion();
            ExecutionContext currentSimContext = simulationManagerService.getContextoSimulacion(simulationId);
    
            if (currentSimContext == null) {
                throw new RuntimeException("No se pudo crear el contexto de simulación.");
            }
    
            // 2. C-FIX: Procesar cada archivo SOLO SI su ID fue proporcionado.
    
            // Pedidos (Asumimos que es obligatorio)
            String fileIdPedidos = request.getFileIdPedidos();
            if (fileIdPedidos == null || fileIdPedidos.isBlank()) {
                throw new IllegalArgumentException("El archivo de pedidos es obligatorio.");
            }
            String contenidoPedidos = new String(archivoService.obtenerArchivo(fileIdPedidos), StandardCharsets.UTF_8);
            Map<Integer, List<Pedido>> pedidosPorTiempo = ParseadorArchivos.parsearPedidosPorTiempo(contenidoPedidos);
            currentSimContext.setPedidosPorTiempo(pedidosPorTiempo);
            List<Pedido> initialPedidosFromFile = pedidosPorTiempo.getOrDefault(0, new ArrayList<>());
            currentSimContext.setPedidos(new ArrayList<>(initialPedidosFromFile));
            if (currentSimContext.getPedidosPorTiempo().containsKey(0)) {
                 currentSimContext.getPedidosPorTiempo().remove(0); 
            }
    
            // Bloqueos (Opcional)
            String fileIdBloqueos = request.getFileIdBloqueos();
            if (fileIdBloqueos != null && !fileIdBloqueos.isBlank()) {
                String contenidoBloqueos = new String(archivoService.obtenerArchivo(fileIdBloqueos), StandardCharsets.UTF_8);
                List<Bloqueo> bloqueos = ParseadorArchivos.parsearBloqueos(contenidoBloqueos);
                currentSimContext.setBloqueos(bloqueos);
            }
    
            // Averías (Opcional)
            String fileIdAverias = request.getFileIdAverias();
            if (fileIdAverias != null && !fileIdAverias.isBlank()) {
                String contenidoAverias = new String(archivoService.obtenerArchivo(fileIdAverias), StandardCharsets.UTF_8);
                Map<String, Map<String, String>> averiasPorTurno = ParseadorArchivos.parsearAverias(contenidoAverias);
                currentSimContext.setAveriasPorTurno(averiasPorTurno);
            }
    
            // Mantenimientos (Opcional, si lo tienes)
            // String fileIdMantenimientos = request.getFileIdMantenimientos();
            // if (fileIdMantenimientos != null && !fileIdMantenimientos.isBlank()) { ... }
            
            // 3. Limpiar estados y preparar la respuesta DTO. Esta parte está bien.
            currentSimContext.getEventosEntrega().clear();
            currentSimContext.getAveriasAplicadas().clear();
            currentSimContext.getCamionesInhabilitados().clear();
            currentSimContext.setRutas(new ArrayList<>());
    
            String nombre = request.getNombreSimulacion() != null ? request.getNombreSimulacion() : "Simulación " + simulationId.substring(0, 8);
            SimulationStatusDTO status = new SimulationStatusDTO();
            status.setSimulationId(simulationId);
            status.setNombreSimulacion(nombre);
            status.setEstado("INITIALIZED"); 
            status.setAvance(0);
    
            return status;
    
        } catch (Exception e) {
            // Tu `System.err.println` me ayudó a encontrar esto. ¡Excelente!
            System.err.println("Error starting simulation: " + e.getMessage());
            throw new RuntimeException("Error al iniciar simulación con request: " + e.getMessage(), e);
        }
    }

    // --- Métodos auxiliares privados ---

    private void aplicarNuevasRutas(int tiempoActual, List<Ruta> rutas, ExecutionContext contexto) {
        if (rutas == null || rutas.isEmpty()) {
            return;
        }

        System.out.printf("🗺️ Aplicando %d nuevas rutas en t+%d...%n", rutas.size(), contexto.getCurrentTime());

        for (Ruta ruta : rutas) {
            CamionEstado camionReal = findCamion(ruta.getCamionId(), contexto);

            // Solo asignar si el camión está realmente disponible
            if (camionReal == null || !camionReal.estaLibre(contexto.getCurrentTime())) {
                System.out.printf("⚠️ Camión %s no está disponible, ruta descartada.%n", ruta.getCamionId());
                continue;
            }

            // Obtenemos los objetos Pedido reales a partir de los IDs de la ruta
            List<Pedido> pedidosDeRuta = ruta.getPedidoIds().stream()
                .map(id -> findPedidoById(id, contexto))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            if (pedidosDeRuta.isEmpty()) continue;

            // --- TRADUCCIÓN DEL PLAN A ACCIÓN ---

            // 1. Marcar los pedidos como programados para que otros camiones no los tomen
            pedidosDeRuta.forEach(p -> p.setProgramado(true));

            // 2. Construir la trayectoria completa de puntos
            List<java.awt.Point> trayectoriaCompleta = new ArrayList<>();
            java.awt.Point puntoPartida = new java.awt.Point(camionReal.getX(), camionReal.getY());

            int horaActual = tiempoActual;
            for (Pedido p : pedidosDeRuta) {
                List<Point> segmento = findPathAStar(
                    new Point(puntoPartida.x, puntoPartida.y), 
                    new Point(p.getX(), p.getY()), 
                    horaActual, 
                    contexto
                );
                
                if (segmento == null || segmento.isEmpty()) {
                    if (!new Point(puntoPartida.x, puntoPartida.y).equals(new Point(p.getX(), p.getY()))) {
                        System.err.printf("Error: No se pudo generar segmento de ruta para Pedido %d%n", p.getId());
                        continue; // Saltar este pedido si no hay ruta
                    }
                } else {
                    // Convert internal Point to java.awt.Point for the model
                    List<java.awt.Point> segmentoAWT = segmento.stream()
                        .map(s -> new java.awt.Point(s.x, s.y))
                        .collect(Collectors.toList());
                        
                    trayectoriaCompleta.addAll(segmentoAWT);
                    horaActual += segmento.size();
                    
                    // Programar el evento de entrega para este pedido
                    contexto.getEventosEntrega().add(new EntregaEvent(horaActual, camionReal.getPlantilla().getId(), p));
                    
                    puntoPartida = new java.awt.Point(p.getX(), p.getY());
                }
            }

            // 3. Dar la orden final al camión
            if (!trayectoriaCompleta.isEmpty()) {
                // Actualizamos el estado del camión
                camionReal.getPedidosCargados().clear();
                camionReal.getPedidosCargados().addAll(pedidosDeRuta);
                camionReal.setStatus(CamionEstado.TruckStatus.DELIVERING);
                camionReal.setRuta(trayectoriaCompleta);
                camionReal.setTiempoLibre(horaActual + 15); // 15 minutos adicionales para descarga
                
                System.out.printf("✅ Camión %s asignado a nueva ruta con %d pedidos. Estado: %s, Ocupado hasta t+%d%n",
                                  camionReal.getPlantilla().getId(),
                                  pedidosDeRuta.size(),
                                  camionReal.getStatus(),
                                  camionReal.getTiempoLibre());
            }
        }
    }

    private Pedido findPedidoById(int pedidoId, ExecutionContext context) {
        return context.getPedidos().stream()
            .filter(p -> p.getId() == pedidoId)
            .findFirst()
            .orElse(null);
    }

    private CamionEstado findCamion(String camionId, ExecutionContext estado) {
        return estado.getCamiones().stream()
                .filter(c -> c.getPlantilla().getId().equals(camionId))
                .findFirst().orElse(null);
    }

    private String turnoDeMinuto(int t) {
        int mod = t % 1440;
        if (mod < 480) return "T1";
        else if (mod < 960) return "T2";
        else return "T3";
    }

    private boolean isBlockedMove(int x, int y, int t, ExecutionContext estado) {
        for (Bloqueo b : estado.getBloqueos()) {
            // The logic for checking if a point is inside a polygonal block is complex.
            // Assuming a simplified check here. The actual logic resides in Bloqueo.estaBloqueado
            if (b.isActiveAt(t) && b.estaBloqueado(t, new java.awt.Point(x,y))) {
                 return true;
            }
        }
        return false;
    }

    private List<Point> findPathAStar(Point start, Point end, int startTime, ExecutionContext estado) {
        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingInt(node -> node.fCost));
        Set<Point> closedSet = new HashSet<>();
        Map<Point, Node> allNodes = new HashMap<>();

        Node startNode = new Node(start, null, 0, start.manhattanDistance(end));
        openSet.add(startNode);
        allNodes.put(start, startNode);

        while (!openSet.isEmpty()) {
            Node currentNode = openSet.poll();

            if (currentNode.position.equals(end)) {
                return reconstructPath(currentNode);
            }

            closedSet.add(currentNode.position);

            for (Point neighborPos : getNeighbors(currentNode.position)) {
                if (closedSet.contains(neighborPos) || isBlockedMove(neighborPos.x, neighborPos.y, startTime + currentNode.gCost + 1, estado)) {
                    continue;
                }

                int tentativeGCost = currentNode.gCost + 1;
                Node neighborNode = allNodes.getOrDefault(neighborPos, new Node(neighborPos));

                if (tentativeGCost < neighborNode.gCost) {
                    neighborNode.parent = currentNode;
                    neighborNode.gCost = tentativeGCost;
                    neighborNode.hCost = neighborPos.manhattanDistance(end);
                    neighborNode.fCost = neighborNode.gCost + neighborNode.hCost;
                    
                    if (!openSet.contains(neighborNode)) {
                        openSet.add(neighborNode);
                    }
                    allNodes.put(neighborPos, neighborNode);
                }
            }
        }
        return null; // No path found
    }

    private List<Point> getNeighbors(Point p) {
        List<Point> neighbors = new ArrayList<>();
        neighbors.add(new Point(p.x + 1, p.y));
        neighbors.add(new Point(p.x - 1, p.y));
        neighbors.add(new Point(p.x, p.y + 1));
        neighbors.add(new Point(p.x, p.y - 1));
        return neighbors;
    }

    private List<Point> reconstructPath(Node endNode) {
        LinkedList<Point> path = new LinkedList<>();
        Node currentNode = endNode;
        while (currentNode != null) {
            path.addFirst(currentNode.position);
            currentNode = currentNode.parent;
        }
        path.removeFirst(); 
        return path;
    }
}
