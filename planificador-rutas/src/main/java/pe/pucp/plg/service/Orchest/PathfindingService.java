package pe.pucp.plg.service.Orchest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.springframework.stereotype.Service;

import java.awt.Point;

import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.context.ExecutionContext;

@Service
public class PathfindingService {
 
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
    
    public List<Point> findPathAStar(Point start, Point end, LocalDateTime startTime, ExecutionContext estado) {
        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingInt(node -> node.fCost));
        Set<Point> closedSet = new HashSet<>();
        Map<Point, Node> allNodes = new HashMap<>();

        Node startNode = new Node(start, null, 0, manhattanDistance(start, end));
        openSet.add(startNode);
        allNodes.put(start, startNode);

        while (!openSet.isEmpty()) {
            Node currentNode = openSet.poll();

            if (currentNode.position.equals(end)) {
                return reconstructPath(currentNode);
            }

            closedSet.add(currentNode.position);

            for (Point neighborPos : getNeighbors(currentNode.position)) {
                LocalDateTime tiempoLlegadaVecino = startTime.plusMinutes(currentNode.gCost + 1);

                // 1. ¿El vecino que estamos evaluando es el destino final?
                boolean esDestinoFinal = neighborPos.equals(end);
                
                // 2. ¿El nodo que estamos expandiendo es el punto de partida original de esta búsqueda?
                boolean enElPuntoDePartidaOriginal = currentNode.position.equals(start);

                // 3. El movimiento hacia el vecino está bloqueado si el vecino está bloqueado...
                boolean movimientoBloqueado = isBlockedMove(neighborPos.x, neighborPos.y, tiempoLlegadaVecino, estado) ||
                                            (isBlockedMove(currentNode.position.x, currentNode.position.y, tiempoLlegadaVecino, estado) && !enElPuntoDePartidaOriginal);

                // 4. La condición final completa para ignorar un vecino:
                if (closedSet.contains(neighborPos) || (movimientoBloqueado && !esDestinoFinal)) {
                    continue;
                }

                int tentativeGCost = currentNode.gCost + 1;
                Node neighborNode = allNodes.getOrDefault(neighborPos, new Node(neighborPos));

                if (tentativeGCost < neighborNode.gCost) {
                    neighborNode.parent = currentNode;
                    neighborNode.gCost = tentativeGCost;
                    neighborNode.hCost = manhattanDistance(neighborPos, end);
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

    private int manhattanDistance(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }

    private boolean isBlockedMove(int x, int y, LocalDateTime t, ExecutionContext estado) {
        for (Bloqueo b : estado.getBloqueosPorDia()) { 
            if (b.isActiveAt(t) && b.estaBloqueado(t, new Point(x, y))) {
                return true;
            }
        }
        return false;
    }

    
    /**
     * Construye una ruta Manhattan entre dos puntos, teniendo en cuenta bloqueos.
     * Si hay un bloqueo, recurre al algoritmo A* para encontrar una ruta alternativa.
     * 
     * @param x1 Coordenada x del punto inicial
     * @param y1 Coordenada y del punto inicial
     * @param x2 Coordenada x del punto destino
     * @param y2 Coordenada y del punto destino
     * @param tiempoInicial Tiempo en el que se inicia el recorrido
     * @param estado Contexto de ejecución con información de bloqueos
     * @return Lista de puntos que forman la ruta
     */
    public List<Point> buildManhattanPath(int x1, int y1, int x2, int y2, LocalDateTime tiempoInicial, ExecutionContext estado) {
        if (x1 == x2 && y1 == y2) {
            return Collections.emptyList();
        }
        List<Point> path = new ArrayList<>();
        path = findPathAStar(new Point(x1, y1), new Point(x2, y2), tiempoInicial, estado);
        if (path == null || path.isEmpty()) {
            System.err.printf("Error: No hay ruta de (%d,%d) a (%d,%d) en %s debido a bloqueos%n", 
                    x1, y1, x2, y2, tiempoInicial);
            return Collections.emptyList();
        }
        return path;
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
