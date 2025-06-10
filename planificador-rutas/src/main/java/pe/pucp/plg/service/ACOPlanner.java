package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.model.*;
import pe.pucp.plg.state.SimulacionEstado;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ACOPlanner {

    private static final int ITERACIONES = 50; // ajustar según tus pruebas
    private static final int HORMIGAS = 30;    // idem
    private static final double ALPHA = 1.0;
    private static final double BETA = 2.0;
    private static final double RHO = 0.1;     // evaporación
    private static final double Q = 100.0;     // feromona depositada

    @Autowired
    private CamionService camionService;
    @Autowired
    private SimulacionEstado estado;

    // ------------------------------------------------------------
    // 1) Ejecución del algoritmo ACO para el VRP
    // ------------------------------------------------------------
    public List<Ruta> ejecutarACO(List<Pedido> pedidosActivos, List<CamionEstado> flotaEstado, int tiempoActual) {
        int V = flotaEstado.size(), N = pedidosActivos.size();
        double[][] tau = new double[V][N];
        for (double[] row : tau) Arrays.fill(row, 1.0);

        List<Ruta> mejorSol = null;
        double mejorCoste = Double.MAX_VALUE;

        for (int it = 0; it < ITERACIONES; it++) {
            List<List<Ruta>> soluciones = new ArrayList<>();
            // Construir soluciones con HORMIGAS
            for (int h = 0; h < HORMIGAS; h++) {
                List<Integer> noAsignados = new ArrayList<>();
                for (int i = 0; i < N; i++) noAsignados.add(i);

                List<CamionEstado> clonedFlota = deepCopyFlota(flotaEstado);
                List<Ruta> rutas = initRutas(clonedFlota);

                while (!noAsignados.isEmpty()) {
                    double[][] prob = calcularProbabilidades(rutas, pedidosActivos, noAsignados, tau, tiempoActual);
                    Seleccion sel = muestrearPar(prob, noAsignados);
                    boolean ok = asignarPedidoARuta(sel.camionIdx, sel.pedidoIdx, rutas, pedidosActivos, tiempoActual);
                    noAsignados.remove(Integer.valueOf(sel.pedidoIdx));
                }
                soluciones.add(rutas);
            }

            // Evaporación
            for (int v = 0; v < V; v++)
                for (int i = 0; i < N; i++)
                    tau[v][i] *= (1 - RHO);

            // Depósito + búsqueda de mejor
            Map<String, Integer> idToIndex = new HashMap<>();
            for (int i = 0; i < flotaEstado.size(); i++) {
                idToIndex.put(flotaEstado.get(i).id, i);
            }
            for (List<Ruta> sol : soluciones) {
                double coste = calcularCosteTotal(sol);
                if (coste < mejorCoste) {
                    mejorCoste = coste;
                    mejorSol = sol;
                }
                for (Ruta ruta : sol) {
                    int v = idToIndex.getOrDefault(ruta.estadoCamion.id, -1);
                    if (v >= 0) {
                        for (int idx : ruta.pedidos) {
                            tau[v][idx] += Q / coste;
                        }
                    }
                }
            }
        }
        return mejorSol != null ? mejorSol : Collections.emptyList();
    }

    // ------------------------------------------------------------
    // 2) Copia profunda de la flota para cada hormiga
    // ------------------------------------------------------------
    private List<CamionEstado> deepCopyFlota(List<CamionEstado> original) {
        List<CamionEstado> copia = new ArrayList<>();
        for (CamionEstado est : original) {
            CamionEstado cl = new CamionEstado();
            cl.id = est.id;
            cl.posX = est.posX;
            cl.posY = est.posY;
            cl.capacidadDisponible = est.capacidadDisponible;
            cl.tiempoLibre = est.tiempoLibre;
            cl.tara = est.tara;
            cl.combustibleDisponible = est.combustibleDisponible;
            copia.add(cl);
        }
        return copia;
    }

    // ------------------------------------------------------------
    // 3) Inicializar rutas vacías (una por camión)
    // ------------------------------------------------------------
    private List<Ruta> initRutas(List<CamionEstado> flota) {
        List<Ruta> rutas = new ArrayList<>();
        for (CamionEstado est : flota) {
            Ruta r = new Ruta();
            r.estadoCamion = est;
            rutas.add(r);
        }
        return rutas;
    }

    // ------------------------------------------------------------
    // 4) Calcular probabilidades (feromonas + heurística)
    // ------------------------------------------------------------
    private double[][] calcularProbabilidades(
            List<Ruta> rutas,
            List<Pedido> pedidosActivos,
            List<Integer> noAsignados,
            double[][] tau,
            int tiempoActual) {

        int V = rutas.size();
        double[][] prob = new double[V][pedidosActivos.size()];
        double minPorKm = 60.0 / 50.0;

        for (int v = 0; v < V; v++) {
            CamionEstado c = rutas.get(v).estadoCamion;

            for (int idx : noAsignados) {
                Pedido p = pedidosActivos.get(idx);

                // 1) filtro capacidad
                if (c.capacidadDisponible < p.getVolumen()) continue;

                // 2) filtro ventana de tiempo
                int dx = Math.abs(c.posX - p.getX());
                int dy = Math.abs(c.posY - p.getY());
                int distKm = dx + dy;
                int tiempoViaje = (int) Math.ceil(distKm * minPorKm);
                if (tiempoActual + tiempoViaje > p.getTiempoLimite()) continue;

                // 3) filtro combustible
                double pesoCargaTon = p.getVolumen() * 0.5;
                double pesoTaraTon  = c.tara / 1000.0;
                double pesoTotalTon = pesoCargaTon + pesoTaraTon;
                double galNecesarios = distKm * pesoTotalTon / 180.0;
                if (c.combustibleDisponible < galNecesarios) continue;

                // heurística + feromona
                double penalTiempo = 1.0 / (1 + Math.max(0, c.tiempoLibre - tiempoActual));
                double eta = 1.0 / (distKm + 1) * penalTiempo;
                prob[v][idx] = Math.pow(tau[v][idx], ALPHA) * Math.pow(eta, BETA);
            }
        }
        return prob;
    }

    // ------------------------------------------------------------
    // 5) Muestreo aleatorio ponderado (ruleta) → Selección
    // ------------------------------------------------------------
    private static class Seleccion { int camionIdx, pedidoIdx; }

    private Seleccion muestrearPar(double[][] prob, List<Integer> noAsignados) {
        double total = 0;
        for (int v = 0; v < prob.length; v++)
            for (int idx : noAsignados)
                total += prob[v][idx];

        double r = Math.random() * total;
        double acumulado = 0;
        for (int v = 0; v < prob.length; v++) {
            for (int idx : noAsignados) {
                acumulado += prob[v][idx];
                if (acumulado >= r) {
                    Seleccion s = new Seleccion();
                    s.camionIdx = v;
                    s.pedidoIdx = idx;
                    return s;
                }
            }
        }
        // fallback
        Seleccion s = new Seleccion();
        s.camionIdx = 0;
        s.pedidoIdx = noAsignados.get(0);
        return s;
    }

    // ------------------------------------------------------------
    // 6) Intento de asignar un pedido a una ruta (con checks)
    // ------------------------------------------------------------
    private boolean asignarPedidoARuta(
            int camionIdx,
            int pedidoIdx,
            List<Ruta> rutas,
            List<Pedido> pedidosActivos,
            int tiempoActual) {

        Ruta ruta = rutas.get(camionIdx);
        CamionEstado c = ruta.estadoCamion;
        Pedido p   = pedidosActivos.get(pedidoIdx);

        if (ruta.pedidos.contains(pedidoIdx)) return false;
        if (c.capacidadDisponible < p.getVolumen()) return false;

        int dx = Math.abs(c.posX - p.getX());
        int dy = Math.abs(c.posY - p.getY());
        int distKm  = dx + dy;
        double minPorKm = 60.0 / 50.0;
        int tiempoViaje = (int) Math.ceil(distKm * minPorKm);

        if (tiempoActual + tiempoViaje > p.getTiempoLimite()) return false;

        double pesoCargaTon = p.getVolumen() * 0.5;
        double pesoTaraTon  = c.tara / 1000.0;
        double pesoTotalTon = pesoTaraTon + pesoCargaTon;
        double galNecesarios = distKm * pesoTotalTon / 180.0;
        if (c.combustibleDisponible < galNecesarios) return false;

        // actualizar estado camión
        c.tiempoLibre = tiempoActual + tiempoViaje;
        c.posX = p.getX();
        c.posY = p.getY();

        double nuevaCapacidad = c.capacidadDisponible - p.getVolumen();
        if (nuevaCapacidad < 0) return false;
        c.capacidadDisponible = nuevaCapacidad;

        ruta.distancia      += distKm;
        ruta.consumo        += galNecesarios;
        c.combustibleDisponible -= galNecesarios;

        ruta.pedidos.add(pedidoIdx);
        return true;
    }

    // ------------------------------------------------------------
    // 7) Costo total de las rutas (suma de consumos)
    // ------------------------------------------------------------
    private double calcularCosteTotal(List<Ruta> sol) {
        return sol.stream().mapToDouble(r -> r.consumo).sum();
    }

    // ------------------------------------------------------------
    // 8) Buscar un camión real en la flota por su id
    // ------------------------------------------------------------
    public Camion findCamion(String id) {
        return estado.getCamiones().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------
    // 9) Verificar si un punto p está bloqueado en timeMin
    // ------------------------------------------------------------
    //private boolean puntoBloqueado(int timeMin, Point p) {
    //    for (Bloqueo b : estado.getBloqueos())
    //        if (b.estaBloqueado(timeMin, p)) return true;
    //    return false;
    //}

    // ------------------------------------------------------------
    // 10) Construir ruta Manhattan, chequeando bloqueos
    // ------------------------------------------------------------
    public List<Point> buildManhattanPath(int x1, int y1, int x2, int y2, int tiempoInicial) {
        List<Point> path = new ArrayList<>();
        Point current = new Point(x1, y1);
        int t = tiempoInicial;

        while (current.x != x2 || current.y != y2) {
            Point prev = new Point(current.x, current.y);
            if      (current.x < x2) current.x++;
            else if (current.x > x2) current.x--;
            else if (current.y < y2) current.y++;
            else                      current.y--;

            Point next = new Point(current.x, current.y);
            int tiempoLlegada = t + 1;

            if (isBlockedMove(prev, next, tiempoLlegada)) {
                // invocar A* si hay bloqueo
                List<Point> alt = findPathAStar(prev.x, prev.y, x2, y2, tiempoLlegada);
                if (alt == null) {
                    throw new RuntimeException(
                            "No hay ruta hacia ("+x2+","+y2+") desde ("+x1+","+y1+") en t+"+tiempoInicial
                    );
                }
                path.addAll(alt);
                return path;
            }
            path.add(next);
            t = tiempoLlegada;
        }
        return path;
    }

    // ------------------------------------------------------------
    // 11) Verifica bloqueo en el tramo prev→next
    // ------------------------------------------------------------
    private boolean isBlockedMove(Point prev, Point next, int timeMin) {
        for (Bloqueo b : estado.getBloqueos()) {
            if (b.coversSegment(prev, next) && b.estaBloqueado(timeMin, next)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------
    // 12) A* considerando bloqueos dinámicos
    // ------------------------------------------------------------
    private static class Node implements Comparable<Node> {
        Point pt; int g, f; Node parent;
        Node(Point pt, int g, int f, Node p) { this.pt = pt; this.g = g; this.f = f; this.parent = p; }
        public int compareTo(Node o) { return Integer.compare(this.f, o.f); }
    }

    public List<Point> findPathAStar(int x1, int y1, int x2, int y2, int tiempo) {
        boolean[][] closed = new boolean[70][50];
        PriorityQueue<Node> open = new PriorityQueue<>();
        open.add(new Node(new Point(x1,y1), 0, manhattan(x1,y1,x2,y2), null));

        while (!open.isEmpty()) {
            Node curr = open.poll();
            int cx = curr.pt.x, cy = curr.pt.y;
            if (cx == x2 && cy == y2) {
                List<Point> ruta = new ArrayList<>();
                for (Node n = curr; n != null; n = n.parent) ruta.add(n.pt);
                Collections.reverse(ruta);
                ruta.remove(0);
                return ruta;
            }
            if (closed[cx][cy]) continue;
            closed[cx][cy] = true;

            for (int[] d : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) {
                int nx = cx + d[0], ny = cy + d[1];
                if (nx < 0 || nx >= 70 || ny < 0 || ny >= 50) continue;
                Point next = new Point(nx, ny);

                boolean bad = false;
                int tLleg = tiempo + curr.g + 1;
                for (Bloqueo b : estado.getBloqueos()) {
                    if (b.estaBloqueado(tLleg, next)) {
                        bad = true; break;
                    }
                }
                if (bad && !(nx == x2 && ny == y2)) continue;
                if (closed[nx][ny]) continue;

                int g2 = curr.g + 1;
                int f2 = g2 + manhattan(nx, ny, x2, y2);
                open.add(new Node(next, g2, f2, curr));
            }
        }
        return null; // o lanzar excepción
    }

    private int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    // ------------------------------------------------------------
    // 13) Un minuto de simulación completo
    // ------------------------------------------------------------
    public int stepOneMinute() {
        // corregido
        int nuevoTiempo = estado.getCurrentTime() + 1;
        estado.setCurrentTime(nuevoTiempo);
        int tiempoActual = nuevoTiempo;
        boolean replanificar = (tiempoActual == 0);

        // 1) Recarga de tanques intermedios cada vez que currentTime % 1440 == 0 (inicio de día)
        if (tiempoActual > 0 && tiempoActual % 1440 == 0) {
            for (Tanque tq : estado.getTanques()) {
                tq.setDisponible(tq.getCapacidadTotal());
            }
            System.out.printf("🔁 t+%d: Tanques recargados a %.1f m³%n",
                    tiempoActual,
                    estado.getTanques().get(0).getCapacidadTotal());
        }

        // 2) Disparar eventos de entrega programados para este minuto
        Iterator<EntregaEvent> itEv = estado.getEventosEntrega().iterator();
        while (itEv.hasNext()) {
            EntregaEvent ev = itEv.next();
            if (ev.time == tiempoActual) {
                System.out.println("▶▶▶ disparando eventoEntrega para Pedido " + ev.pedido.getId());
                double antes = ev.camion.getDisponible();
                ev.camion.setX(ev.pedido.getX());
                ev.camion.setY(ev.pedido.getY());
                ev.camion.setLibreEn(tiempoActual + 15); // 15 min descarga

                double dispAntes = ev.camion.getDisponible();
                if (dispAntes >= ev.pedido.getVolumen()) {
                    ev.camion.setDisponible(dispAntes - ev.pedido.getVolumen());
                } else {
                    System.out.printf("⚠️ Pedido #%d *no* entregado: capacidad insuficiente (%.1f < %.1f)%n",
                            ev.pedido.getId(), dispAntes, ev.pedido.getVolumen());
                }
                ev.pedido.setAtendido(true);
                System.out.printf(
                        "✅ t+%d: Pedido #%d completado por Camión %s en (%d,%d); cap: %.1f→%.1f m³%n",
                        tiempoActual, ev.pedido.getId(), ev.camion.getId(),
                        ev.pedido.getX(), ev.pedido.getY(),
                        antes, ev.camion.getDisponible()
                );
                itEv.remove();

                // 3) Iniciar retorno
                double falta = ev.camion.getCapacidad() - ev.camion.getDisponible();
                int sx = ev.camion.getX(), sy = ev.camion.getY();
                int dxPlant = estado.getDepositoX(), dyPlant = estado.getDepositoY();
                int distMin = Math.abs(sx - dxPlant) + Math.abs(sy - dyPlant);
                Tanque mejor = null;
                for (Tanque tq : estado.getTanques()) {
                    if (tq.getDisponible() >= falta) {
                        int dist = Math.abs(sx - tq.getPosX()) + Math.abs(sy - tq.getPosY());
                        if (dist < distMin) {
                            distMin = dist;
                            mejor = tq;
                        }
                    }
                }
                int destX = (mejor != null ? mejor.getPosX() : dxPlant);
                int destY = (mejor != null ? mejor.getPosY() : dyPlant);
                ev.camion.setReabastecerEnTanque(mejor);

                if (mejor != null) {
                    mejor.setDisponible(mejor.getDisponible() - falta);
                    System.out.printf("🔁 t+%d: Tanque (%d,%d) reservado %.1fm³ → ahora %.1f m³%n",
                            tiempoActual, mejor.getPosX(), mejor.getPosY(), falta, mejor.getDisponible());
                }

                ev.camion.setEnRetorno(true);
                ev.camion.setStatus(Camion.TruckStatus.RETURNING);
                ev.camion.setRetHora(tiempoActual);
                ev.camion.setRetStartX(sx);
                ev.camion.setRetStartY(sy);
                ev.camion.setRetDestX(destX);
                ev.camion.setRetDestY(destY);

                List<Point> returnPath = buildManhattanPath(sx, sy, destX, destY, tiempoActual);
                ev.camion.setRutaActual(returnPath);
                ev.camion.setPasoActual(0);
                ev.camion.getHistory().addAll(returnPath);

                System.out.printf("⏱️ t+%d: Camión %s inicia retorno a (%d,%d) dist=%d%n",
                        tiempoActual, ev.camion.getId(), destX, destY, distMin);
            }
        }
        // 4) Avanzar cada camión en ruta (ida o retorno) un paso
        for (Camion c : estado.getCamiones()) {
            if (c.tienePasosPendientes()) {
                // Llamada a CamionService para que el camión avance UN paso en su rutaActual
                camionService.avanzarUnPaso(c);
            } else if (c.getStatus() == Camion.TruckStatus.RETURNING) {
                // lógica de recarga automática al llegar a depósito (queda igual)
                double falta = c.getCapacidad() - c.getDisponible();
                Tanque tq = c.getReabastecerEnTanque();
                if (tq != null) {
                    System.out.printf("🔄 t+%d: Camión %s llegó a tanque (%d,%d) y recargado a %.1f m³%n",
                            tiempoActual, c.getId(), tq.getPosX(), tq.getPosY(), c.getCapacidad());
                    System.out.printf("🔁      Tanque (%d,%d) quedó con %.1f m³%n",
                            tq.getPosX(), tq.getPosY(), tq.getDisponible());
                } else {
                    System.out.printf("🔄 t+%d: Camión %s llegó a planta (%d,%d) y recargado a %.1f m³%n",
                            tiempoActual, c.getId(), estado.getDepositoX(), estado.getDepositoY(), c.getCapacidad());
                }
                c.setDisponible(c.getCapacidad());
                c.setCombustibleDisponible(c.getCapacidadCombustible());
                c.setEnRetorno(false);
                c.setReabastecerEnTanque(null);
                c.setStatus(Camion.TruckStatus.AVAILABLE);
                c.setLibreEn(tiempoActual + 15);
            }
        }


        // 5) Incorporar nuevos pedidos que llegan en este minuto
        List<Pedido> nuevos = estado.getPedidosPorTiempo().remove(tiempoActual);
        if (nuevos == null) {
            nuevos = Collections.emptyList();
        }

        // 5.a) Calcular capacidad máxima de un camión (suponiendo que todos tienen la misma capacidad)
        double capacidadMaxCamion = estado.getCamiones().stream()
                .mapToDouble(Camion::getCapacidad)   // o getDisponible() si prefieres la disponible inicial
                .max()
                .orElse(0);

        List<Pedido> pedidosAInyectar = new ArrayList<>();
        for (Pedido p : nuevos) {
            double volumenRestante = p.getVolumen();

            if (volumenRestante > capacidadMaxCamion) {
                // 🛠️ Dividir en sub-pedidos de ≤ capacidadMaxCamion
                while (volumenRestante > 0) {
                    double vol = Math.min(capacidadMaxCamion, volumenRestante);
                    int subId = estado.generateUniquePedidoId();
                    Pedido sub = new Pedido(
                            subId,
                            tiempoActual,
                            p.getX(),
                            p.getY(),
                            vol,
                            p.getTiempoLimite()
                    );
                    pedidosAInyectar.add(sub);
                    volumenRestante -= vol;
                }
            } else {
                // cabe entero en un camión
                pedidosAInyectar.add(p);
            }
        }

        // 5.b) Añadir realmente los pedidos (reemplazo de los nuevos originales)
        estado.getPedidos().addAll(pedidosAInyectar);

        for (Pedido p : pedidosAInyectar) {
            System.out.printf("🆕 t+%d: Pedido #%d recibido (destino=(%d,%d), vol=%.1fm³, límite t+%d)%n",
                    tiempoActual, p.getId(), p.getX(), p.getY(), p.getVolumen(), p.getTiempoLimite());
        }
        if (!pedidosAInyectar.isEmpty()) replanificar = true;

        // 6) Comprobar colapso: pedidos vencidos
        Iterator<Pedido> itP = estado.getPedidos().iterator();
        while (itP.hasNext()) {
            Pedido p = itP.next();
            if (!p.isAtendido() && !p.isDescartado() && tiempoActual > p.getTiempoLimite()) {
                System.out.printf("💥 Colapso en t+%d, pedido %d incumplido%n",
                        tiempoActual, p.getId());
                // Marca y elimina para no repetir el colapso
                p.setDescartado(true);
                itP.remove();
            }
        }

        // 7) Averías por turno (T1, T2, T3)
        String turnoActual = turnoDeMinuto(tiempoActual);
        if (!turnoActual.equals(estado.getTurnoAnterior())) {
            estado.setTurnoAnterior(turnoActual);
            estado.getAveriasAplicadas().clear();
            estado.getCamionesInhabilitados().clear();
        }
        Map<String, String> averiasTurno = estado.getAveriasPorTurno()
                .getOrDefault(turnoActual, Collections.emptyMap());
        for (Map.Entry<String, String> entry : averiasTurno.entrySet()) {
            String key = turnoActual + "_" + entry.getKey();
            if (estado.getAveriasAplicadas().contains(key)) continue;
            Camion c = findCamion(entry.getKey());
            if (c != null && c.getLibreEn() <= tiempoActual) {
                int penal = entry.getValue().equals("T1") ? 30 :
                        entry.getValue().equals("T2") ? 60 : 90;
                c.setLibreEn(tiempoActual + penal);
                estado.getAveriasAplicadas().add(key);
                estado.getCamionesInhabilitados().add(c.getId());
                replanificar = true;
                System.out.printf("🚨 t+%d: Camión %s sufre avería tipo %s, penal=%d%n",
                        tiempoActual, c.getId(), entry.getValue(), penal);
            }
        }
        Iterator<String> it = estado.getCamionesInhabilitados().iterator();
        while (it.hasNext()) {
            Camion c = findCamion(it.next());
            if (c != null && c.getLibreEn() <= tiempoActual) {
                it.remove(); replanificar = true;
            }
        }

        // 8) Construir estado “ligero” de la flota disponible para ACO
        List<CamionEstado> flotaEstado = estado.getCamiones().stream()
                .filter(c -> c.getStatus() == Camion.TruckStatus.AVAILABLE)
                .map(c -> {
                    CamionEstado est = new CamionEstado();
                    est.id = c.getId();
                    est.posX = c.getX();
                    est.posY = c.getY();
                    est.capacidadDisponible = c.getDisponible();
                    est.tiempoLibre = c.getLibreEn();
                    est.tara = c.getTara();
                    est.combustibleDisponible = c.getCombustibleDisponible();
                    return est;
                })
                .collect(Collectors.toList());

        // 9) Determinar candidatos a replanificar
        Map<Pedido, Integer> entregaActual = new HashMap<>();
        for (EntregaEvent ev : estado.getEventosEntrega()) {
            entregaActual.put(ev.pedido, ev.time);
        }
        List<Pedido> pendientes = estado.getPedidos().stream()
                .filter(p -> !p.isAtendido() && !p.isDescartado() && !p.isProgramado() && p.getTiempoCreacion() <= tiempoActual)
                .collect(Collectors.toList());

        List<Pedido> candidatos = new ArrayList<>();
        for (Pedido p : pendientes) {
            if (tiempoActual + 60 >= p.getTiempoLimite()) {
                candidatos.add(p);
                continue;
            }
            Integer tPrev = entregaActual.get(p);
            if (tPrev == null) {
                candidatos.add(p);
            } else {
                int mejorAlt = tPrev;
                for (CamionEstado est : flotaEstado) {
                    if (est.capacidadDisponible < p.getVolumen()) continue;
                    int dt = Math.abs(est.posX - p.getX()) + Math.abs(est.posY - p.getY());
                    int llegada = tiempoActual + dt;
                    if (llegada < mejorAlt) mejorAlt = llegada;
                }
                if (mejorAlt < tPrev) candidatos.add(p);
            }
        }
        candidatos.removeIf(p -> {
            Integer entregaMin = entregaActual.get(p);
            return entregaMin != null && entregaMin - tiempoActual <= 1;
        });

        // 10) Replanificación ACO si procede
        if (replanificar && !candidatos.isEmpty()) {
            System.out.printf("⏲️ t+%d: Replanificando, candidatos=%s%n",
                    tiempoActual, candidatos.stream().map(Pedido::getId).collect(Collectors.toList()));

            // A) Cancelar eventos de entrega futuros de esos candidatos
            Set<Integer> idsCandidatos = candidatos.stream().map(Pedido::getId).collect(Collectors.toSet());
            estado.getEventosEntrega().removeIf(ev -> idsCandidatos.contains(ev.pedido.getId()));

            // B) Desprogramar pedidos
            for (Pedido p : candidatos) p.setProgramado(false);

            //  C) Ejecutar ACO
            List<Ruta> rutas = ejecutarACO(candidatos, flotaEstado, tiempoActual);

            System.out.printf("    → Rutas devueltas para %s%n",
                    rutas.stream()
                            .flatMap(r -> r.pedidos.stream())
                            .map(i -> candidatos.get(i).getId())
                            .collect(Collectors.toList()));

            aplicarRutas(tiempoActual, rutas, candidatos);
            estado.setRutas(rutas);
        }
        return estado.getCurrentTime();
    }

    // ------------------------------------------------------------
    // Métodos privados auxiliares copiados de ACOPlanner original
    // ------------------------------------------------------------
    private boolean esDesvioValido(Camion c, Pedido p, int tiempoActual) {
        double disponible = c.getDisponible();
        int hora = tiempoActual;
        int currX = c.getX(), currY = c.getY();
        int d = Math.abs(currX - p.getX()) + Math.abs(currY - p.getY());
        int tViaje = (int) Math.ceil(d * (60.0 / 50.0));
        hora += tViaje;
        if (hora > p.getTiempoLimite()) return false;
        disponible -= p.getVolumen();
        if (disponible < 0) return false;

        int simX = p.getX(), simY = p.getY();
        for (Pedido orig : c.getRutaPendiente()) {
            d = Math.abs(simX - orig.getX()) + Math.abs(simY - orig.getY());
            tViaje = (int) Math.ceil(d * (60.0 / 50.0));
            hora += tViaje;
            if (hora > orig.getTiempoLimite() || (disponible -= orig.getVolumen()) < 0) {
                return false;
            }
            simX = orig.getX(); simY = orig.getY();
        }
        return true;
    }

    private int posicionOptimaDeInsercion(Camion c, Pedido p, int tiempoActual) {
        List<Pedido> originales = c.getRutaPendiente();
        int mejorIdx = originales.size();
        int mejorLlegada = Integer.MAX_VALUE;

        for (int idx = 0; idx <= originales.size(); idx++) {
            double disponible = c.getDisponible();
            int hora = tiempoActual;
            int simX = c.getX(), simY = c.getY();

            List<Pedido> prueba = new ArrayList<>(originales);
            prueba.add(idx, p);

            boolean valido = true;
            for (Pedido q : prueba) {
                int d = Math.abs(simX - q.getX()) + Math.abs(simY - q.getY());
                int tViaje = (int) Math.ceil(d * (60.0 / 50.0));
                hora += tViaje;
                if (hora > q.getTiempoLimite() || (disponible -= q.getVolumen()) < 0) {
                    valido = false;
                    break;
                }
                simX = q.getX(); simY = q.getY();
            }

            if (valido && hora < mejorLlegada) {
                mejorLlegada = hora;
                mejorIdx = idx;
            }
        }
        return mejorIdx;
    }

    private void aplicarRutas(int tiempoActual, List<Ruta> rutas, List<Pedido> activos) {
        rutas.removeIf(r -> r.pedidos == null || r.pedidos.isEmpty());

        // A) Filtrar rutas que no caben en la flota real
        for (Iterator<Ruta> itR = rutas.iterator(); itR.hasNext(); ) {
            Ruta r = itR.next();
            Camion real = findCamion(r.estadoCamion.id);
            double disponible = real.getDisponible();
            boolean allFit = true;
            for (int idx : r.pedidos) {
                if (disponible < activos.get(idx).getVolumen()) {
                    allFit = false;
                    break;
                }
                disponible -= activos.get(idx).getVolumen();
            }
            if (!allFit) {
                System.out.printf("⚠ t+%d: Ruta descartada para %s (no cabe volumen) → %s%n",
                        tiempoActual, real.getId(),
                        r.pedidos.stream().map(i -> activos.get(i).getId()).collect(Collectors.toList()));
                itR.remove();
            }
        }

        // B) Aplicar cada ruta al estado real
        for (Ruta ruta : rutas) {
            Camion camion = findCamion(ruta.estadoCamion.id);
            Pedido nuevo = activos.get(ruta.pedidos.get(0));

            if (camion.getStatus() == Camion.TruckStatus.DELIVERING
                    && esDesvioValido(camion, nuevo, tiempoActual)
                    && camion.getDisponible() >= nuevo.getVolumen()) {

                int idx = posicionOptimaDeInsercion(camion, nuevo, tiempoActual);
                camion.getRutaPendiente().add(idx, nuevo);
                camion.setDisponible(camion.getDisponible() - nuevo.getVolumen());
                System.out.printf("🔀 t+%d: Desvío – insertado Pedido #%d en %s en posición %d%n",
                        tiempoActual, nuevo.getId(), camion.getId(), idx);

                int cx = camion.getX(), cy = camion.getY();
                List<Point> path = buildManhattanPath(cx, cy, nuevo.getX(), nuevo.getY(), tiempoActual);
                int dist = Math.abs(cx - nuevo.getX()) + Math.abs(cy - nuevo.getY());
                int tViaje = (int) Math.ceil(dist * (60.0 / 50.0));

                camion.setRutaActual(path);
                camion.getHistory().addAll(path);
                estado.getEventosEntrega().add(new EntregaEvent(tiempoActual + tViaje, camion, nuevo));
                nuevo.setProgramado(true);
                System.out.printf("🕒 eventoEntrega programado (desvío) t+%d → (%d,%d)%n",
                        tiempoActual + tViaje, nuevo.getX(), nuevo.getY());

            } else {
                // Asignación normal
                camion.getRutaPendiente().clear();
                camion.getRutaPendiente().add(nuevo);
                camion.setStatus(Camion.TruckStatus.DELIVERING);

                int cx = camion.getX(), cy = camion.getY();
                for (int pedidoIdx : ruta.pedidos) {
                    Pedido p = activos.get(pedidoIdx);
                    if (camion.getDisponible() < p.getVolumen()) {
                        System.out.printf("⚠ t+%d: Camión %s sin espacio para Pedido #%d%n",
                                tiempoActual, camion.getId(), p.getId());
                        continue;
                    }
                    System.out.printf("⏱️ t+%d: Asignando Pedido #%d al Camión %s%n",
                            tiempoActual, p.getId(), camion.getId());

                    List<Point> path = buildManhattanPath(cx, cy, p.getX(), p.getY(), tiempoActual);
                    int dist = Math.abs(cx - p.getX()) + Math.abs(cy - p.getY());
                    int tViaje = (int) Math.ceil(dist * (60.0 / 50.0));

                    camion.setRutaActual(path);
                    camion.setPasoActual(0);
                    camion.getHistory().addAll(path);
                    p.setProgramado(true);

                    estado.getEventosEntrega().add(new EntregaEvent(
                            tiempoActual + tViaje, camion, p
                    ));
                    System.out.printf("🕒 eventoEntrega programado t+%d → (%d,%d)%n",
                            tiempoActual + tViaje, p.getX(), p.getY());
                    Point last = path.get(path.size() - 1);
                    // camion.setX(p.getX());
                    // camion.setY(p.getY());
                    cx = last.x;; cy = last.y;
                }
            }
        }
    }

    // ------------------------------------------------------------
    // 14) Conversor turno a partir de minuto (“T1”|“T2”|“T3”)
    // ------------------------------------------------------------
    private String turnoDeMinuto(int t) {
        int mod = t % 1440;
        if (mod < 480) return "T1";      // 00:00–07:59
        else if (mod < 960) return "T2"; // 08:00–15:59
        else return "T3";                // 16:00–23:59
    }
}