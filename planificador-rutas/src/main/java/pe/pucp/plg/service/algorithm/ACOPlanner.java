package pe.pucp.plg.service.algorithm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.EntregaEvent;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.context.SimulacionEstado;
import pe.pucp.plg.model.state.CamionDinamico;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;
import pe.pucp.plg.service.CamionService;

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
    public ACOPlanner(SimulacionEstado estado) {
        this.estado = estado;
    }

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
                    asignarPedidoARuta(sel.camionIdx, sel.pedidoIdx, rutas, pedidosActivos, tiempoActual);
                    noAsignados.remove(Integer.valueOf(sel.pedidoIdx));
                }
                if (!noAsignados.isEmpty()) {
                    System.out.printf("⚠️ [ACO] Iteración hormiga #%d – pedidos NO asignados: %s%n",
                            h,
                            noAsignados.stream().map(i -> pedidosActivos.get(i).getId()).collect(Collectors.toList())
                    );
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
        if (mejorSol == null || mejorSol.isEmpty()) {
            System.out.printf("⚠️ ACO no pudo generar solución para pedidos: %s%n",
                    pedidosActivos.stream().map(p -> "#" + p.getId()).collect(Collectors.joining(", "))
            );
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
    public CamionDinamico findCamion(String id) {
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
            // 1) Solo bloqueos activos en este minuto
            if (!b.isActiveAt(timeMin)) continue;
            // 2) Si el punto "next" está en ese segmento bloqueado, lo bloquea
            if (b.estaBloqueado(timeMin, next)) {
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
        boolean[][] closed = new boolean[71][51];
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
                if (nx < 0 || nx >= 71 || ny < 0 || ny >= 51) continue;
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
            for (TanqueDinamico tq : estado.getTanques()) {
                tq.setDisponible(tq.getCapacidadTotal());
            }
            System.out.printf("🔁 t+%d: Tanques recargados a %.1f m³%n",
                    tiempoActual,
                    estado.getTanques().get(0).getCapacidadTotal());
        }

        // 2) Disparar eventos de entrega programados para este minuto
        triggerScheduledDeliveries(tiempoActual);
        // asegurar
        for (CamionDinamico c : estado.getCamiones()) {
            // sólo reasignamos estado SI YA HA TERMINADO LA RE-CARGA _y_ NO está volviendo
            if (c.getStatus() != CamionDinamico.TruckStatus.RETURNING
                    && c.getLibreEn() <= tiempoActual) {
                c.setStatus(
                        c.getRutaActual().isEmpty()
                                ? CamionDinamico.TruckStatus.AVAILABLE
                                : CamionDinamico.TruckStatus.DELIVERING
                );
            }
        }
        // 4) Avanzar o procesar retorno y entregas por separado
        for (CamionDinamico c : estado.getCamiones()) {
            // 0) Está descargando/recargando => no avanza
            if (c.getLibreEn() > tiempoActual) {
                System.out.printf("⏱️ t+%d: Camión %s en servicio, libre en t+%d%n",
                        tiempoActual, c.getId(), c.getLibreEn());
                continue;
            }
            // 1) Finalizó la ruta de entregas → arranca retorno
            if (c.getStatus() == CamionDinamico.TruckStatus.DELIVERING
                    && !c.tienePasosPendientes()) {
                System.out.printf("🚚 t+%d: Camión %s termina entregas y comienza retorno%n",
                        tiempoActual, c.getId());
                startReturn(c, tiempoActual, estado.getEventosEntrega());
                continue;
            }

            // 2) Retorno a planta
            if (c.getStatus() == CamionDinamico.TruckStatus.RETURNING) {
                if (c.tienePasosPendientes()) {
                    camionService.avanzarUnPaso(c);
                    System.out.printf("t+%d: → Camión %s avanza (retorno) a (%d,%d)%n",tiempoActual,
                            c.getId(), c.getX(), c.getY());
                } else {
                    // llegó al depósito: programa recarga 15'
                    c.setStatus(CamionDinamico.TruckStatus.AVAILABLE);
                    c.setLibreEn(tiempoActual + 15);
                    c.getRutaPendiente().clear();
                    System.out.printf("🔄 t+%d: Camión %s llegó a planta, recargando hasta t+%d%n",
                            tiempoActual, c.getId(), c.getLibreEn());
                }
                continue;
            }

            // 3) Ruta de entrega/desvío
            if (c.getStatus() == CamionDinamico.TruckStatus.DELIVERING
                    && c.tienePasosPendientes()) {
                camionService.avanzarUnPaso(c);
                if (c.getPedidoDesvio() != null) {
                    System.out.printf("t+%d:→ Camión %s avanza (desvío) a (%d,%d)%n", tiempoActual,
                            c.getId(), c.getX(), c.getY());
                } else {
                    System.out.printf("t+%d:→ Camión %s avanza (entrega) a (%d,%d)%n", tiempoActual,
                            c.getId(), c.getX(), c.getY()) ;
                }
                continue;
            }

            // 4) AVAILABLE con ruta vacía → simplemente espera asignación
        }

        // 5) Incorporar nuevos pedidos que llegan en este minuto
        List<Pedido> nuevos = estado.getPedidosPorTiempo().remove(tiempoActual);
        if (nuevos == null) {
            nuevos = Collections.emptyList();
        }

        // 5.a) Calcular capacidad máxima de un camión (suponiendo que todos tienen la misma capacidad)
        double capacidadMaxCamion = estado.getCamiones().stream()
                .mapToDouble(CamionDinamico::getCapacidad)   // o getDisponible() si prefieres la disponible inicial
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
        List<String> keysAProcesar = new ArrayList<>(averiasTurno.keySet());
        for (String mid : keysAProcesar) {
            String key = turnoActual + "_" + mid;
            if (estado.getAveriasAplicadas().contains(key)) continue;
            CamionDinamico c = findCamion(mid);
            if (c != null && c.getLibreEn() <= tiempoActual) {
                String tipo = averiasTurno.get(mid);
                int penal = tipo.equals("T1") ? 30 : tipo.equals("T2") ? 60 : 90;
                c.setLibreEn(tiempoActual + penal);
                estado.getAveriasAplicadas().add(key);
                estado.getCamionesInhabilitados().add(c.getId());
                replanificar = true;
                System.out.printf("🚨 t+%d: Camión %s sufre avería tipo %s, penal=%d%n",
                        tiempoActual, c.getId(), tipo, penal);
            }
        }
        // limpiar inhabilitados
        Iterator<String> itInh = estado.getCamionesInhabilitados().iterator();
        while (itInh.hasNext()) {
            CamionDinamico c = findCamion(itInh.next());
            if (c != null && c.getLibreEn() <= tiempoActual) {
                itInh.remove();
                replanificar = true;
            }
        }

        // 8) Construir estado “ligero” de la flota disponible para ACO
        List<CamionEstado> flotaEstado = estado.getCamiones().stream()
                .filter(c -> c.getLibreEn() <= tiempoActual && c.getStatus() != CamionDinamico.TruckStatus.UNAVAILABLE)

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
        for (EntregaEvent ev : new ArrayList<>(estado.getEventosEntrega())) {
            if (ev.pedido != null) {
                entregaActual.put(ev.pedido, ev.time);
            }
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
                    tiempoActual, candidatos.stream()
                            .map(Pedido::getId).collect(Collectors.toList()));

            // A) cancelar y desprogramar
            Set<Integer> ids = candidatos.stream()
                    .map(Pedido::getId).collect(Collectors.toSet());
            estado.getEventosEntrega()
                    .removeIf(ev -> ev.pedido != null
                            && ids.contains(ev.pedido.getId()));

            candidatos.forEach(p -> p.setProgramado(false));

            // B) Desvío local con búsqueda del mejor camión
            List<Pedido> sinAsignar = new ArrayList<>();
            for (Pedido p : candidatos) {
                CamionDinamico mejor = null; int mejorDist = Integer.MAX_VALUE;
                for (CamionDinamico c : estado.getCamiones()) {
                    if (c.getLibreEn() > tiempoActual) continue;
                    if (c.getStatus() == CamionDinamico.TruckStatus.UNAVAILABLE) continue;
                    if (c.getDisponible() < p.getVolumen()) continue;
                    int dist = Math.abs(c.getX()-p.getX()) + Math.abs(c.getY()-p.getY());
                    boolean ok = c.getStatus()!= CamionDinamico.TruckStatus.DELIVERING
                            || esDesvioValido(c,p,tiempoActual);
                    if (ok && dist<mejorDist) { mejor=c; mejorDist=dist; }
                }
                if (mejor!=null) {
                    // 1) hacemos backup de la ruta ORIGINAL
                    mejor.setRutaBackup(new ArrayList<>(mejor.getRutaActual()));
                    mejor.setPedidosBackup(new ArrayList<>(mejor.getRutaPendiente()));
                    mejor.setPedidoDesvio(p);

                    // 2) hacemos backup de la ruta ORIGINAL insertamos en pendientes

                    int idx = posicionOptimaDeInsercion(mejor,p,tiempoActual);
                    mejor.getRutaPendiente().add(idx,p);
                    p.setProgramado(true);
                    // 3) si está AVAILABLE arrancamos directo
                    if (mejor.getStatus()== CamionDinamico.TruckStatus.AVAILABLE) {
                        List<Point> ruta = buildManhattanPath(
                                mejor.getX(), mejor.getY(), p.getX(), p.getY(), tiempoActual
                        );
                        int tt = (int)Math.ceil(ruta.size()*(60.0/50.0));
                        mejor.setRutaActual(ruta);
                        mejor.setPasoActual(0);
                        mejor.getHistory().addAll(ruta);
                        mejor.setStatus(CamionDinamico.TruckStatus.DELIVERING);
                        estado.getEventosEntrega()
                                .add(new EntregaEvent(tiempoActual+tt+15, mejor, p));
                    }
                    // 4) si está DELIVERING forzamos reproceso parcial
                    else {
                        // 4.a) Ruta hasta el pedido de desvío
                        List<Point> caminoDesvio = buildManhattanPath(
                                mejor.getX(), mejor.getY(),
                                p.getX(), p.getY(),
                                tiempoActual
                        );
                        if (caminoDesvio == null) continue;  // no hay ruta válida

                        // 4.b) Avanzar el “tiempo virtual” tras llegar y descargar
                        int tiempoTrasDesvio = tiempoActual
                                + caminoDesvio.size()    // pasos
                                + 15;                     // tiempo de descarga

                        // 4.c) Ruta desde el pedido desviado hasta el siguiente antiguo
                        Pedido siguiente = mejor.getRutaPendiente().get(0);
                        List<Point> caminoPost = buildManhattanPath(
                                p.getX(), p.getY(),
                                siguiente.getX(), siguiente.getY(),
                                tiempoTrasDesvio
                        );

                        // 4.d) Combinar trayectos
                        List<Point> nuevaRuta = new ArrayList<>(caminoDesvio);
                        if (caminoPost != null) nuevaRuta.addAll(caminoPost);

                        // 4.e) Aplicar la nueva ruta al camión
                        mejor.getRutaActual().clear();
                        mejor.setRutaActual(nuevaRuta);
                        mejor.setPasoActual(0);
                        mejor.getHistory().addAll(nuevaRuta);

                        // 4.f) Borrar eventos antiguos de entrega para este camión
                        Iterator<EntregaEvent> itEvAux = estado.getEventosEntrega().iterator();
                        while (itEvAux.hasNext()) {
                            if (itEvAux.next().camion.equals(mejor)) itEvAux.remove();
                        }

                        // 4.g) Programar de nuevo los eventos: primero el desvío, luego el resto
                        int ttDesvio = (int)Math.ceil(caminoDesvio.size()*(60.0/50.0));
                        estado.getEventosEntrega()
                                .add(new EntregaEvent(tiempoActual + ttDesvio + 15, mejor, p));

                        if (caminoPost != null) {
                            int ttPost = (int)Math.ceil(nuevaRuta.size()*(60.0/50.0));
                            estado.getEventosEntrega()
                                    .add(new EntregaEvent(tiempoActual + ttPost +15, mejor, siguiente));
                        }

                        System.out.printf("🔀 t+%d: Pedido #%d insertado en %s, recalculando ruta a desvío + resto%n",
                                tiempoActual, p.getId(), mejor.getId());
                    }
                    System.out.printf("🔀 t+%d: Pedido #%d asignado a Camión %s (desvío)%n",
                            tiempoActual, p.getId(), mejor.getId());
                } else {
                    sinAsignar.add(p);
                }
            }


            // C) El resto va al ACO habitual
            if (!sinAsignar.isEmpty()) {
                System.out.printf("📦 ACO recibe pedidos sin asignar: %s%n",
                        sinAsignar.stream().map(Pedido::getId).collect(Collectors.toList()));
                sinAsignar.removeIf(p -> p.isProgramado() || p.isAtendido());
                List<Ruta> rutas = ejecutarACO(sinAsignar, flotaEstado, tiempoActual);
                System.out.printf("    → Rutas ACO para %s%n",
                        rutas.stream()
                                .flatMap(r -> r.pedidos.stream())
                                .map(i -> sinAsignar.get(i).getId())
                                .collect(Collectors.toList()));
                aplicarRutas(tiempoActual, rutas, sinAsignar);
                estado.setRutas(rutas);
            }

        }
        return estado.getCurrentTime();
    }
    // 2) Disparar eventos de entrega programados para este minuto
    private void triggerScheduledDeliveries(int tiempoActual) {
        Iterator<EntregaEvent> it = estado.getEventosEntrega().iterator();
        // Lista auxiliar para los nuevos eventos que queramos programar
        List<EntregaEvent> nuevosEventos = new ArrayList<>();

        while (it.hasNext()) {
            EntregaEvent ev = it.next();
            if (ev.time != tiempoActual) continue;
            it.remove();  // elimina con el iterador

            // ——— Evento de “fin de retorno” ———
            if (ev.pedido == null) {
                CamionDinamico cam = ev.camion;
                // Guardamos el tanque destino para el mensaje
                TanqueDinamico destino = cam.getReabastecerEnTanque();

                // Recarga interna
                cam.setDisponible(cam.getCapacidad());
                cam.setCombustibleDisponible(cam.getCapacidadCombustible());
                cam.setEnRetorno(false);
                cam.setStatus(CamionDinamico.TruckStatus.AVAILABLE);
                cam.setLibreEn(tiempoActual + 15);
                cam.getRutaActual().clear();
                cam.setPasoActual(0);
                cam.getRutaPendiente().clear();
                cam.setReabastecerEnTanque(null);

                // Mensaje según destino
                if (destino != null) {
                    System.out.printf("🔄 t+%d: Camión %s llegó a tanque (%d,%d) y recargado a %.1f m³%n",
                            tiempoActual, cam.getId(),
                            destino.getPosX(), destino.getPosY(),
                            cam.getCapacidad());
                } else {
                    System.out.printf("🔄 t+%d: Camión %s llegó a planta (%d,%d) y recargado a %.1f m³%n",
                            tiempoActual, cam.getId(),
                            estado.getDepositoX(), estado.getDepositoY(),
                            cam.getCapacidad());
                }
                continue;
            }

            // ——— Evento de entrega de pedido ———
            CamionDinamico camion = ev.camion;
            Pedido pedido = ev.pedido;

            // 1) Llegada y descarga
            double antes = camion.getDisponible();
            camion.setX(pedido.getX());
            camion.setY(pedido.getY());
            camion.setLibreEn(tiempoActual + 15);

            double dispAntes = camion.getDisponible();
            if (dispAntes >= pedido.getVolumen()) {
                camion.setDisponible(dispAntes - pedido.getVolumen());
                pedido.setAtendido(true);

                camion.getRutaPendiente().removeIf(p -> p.getId() == pedido.getId());

                System.out.printf(
                        "✅ t+%d: Pedido #%d completado por Camión %s en (%d,%d); cap: %.1f→%.1f m³%n",
                        tiempoActual, pedido.getId(), camion.getId(),
                        pedido.getX(), pedido.getY(),
                        antes, camion.getDisponible()
                );
            } else {
                System.out.printf(
                        "⚠️ Pedido #%d *no* entregado: capacidad insuficiente (%.1f < %.1f)%n",
                        pedido.getId(), dispAntes, pedido.getVolumen()
                );
            }

            // 2) Si era un desvío, restaurar ruta original y reprogramar pendientes
            if (pedido.equals(camion.getPedidoDesvio())) {
                // 1) Recupero la lista de pendientes que había antes del desvío
                List<Pedido> pendientes = camion.getPedidosBackup();
                camion.getRutaPendiente().clear();
                camion.getRutaPendiente().addAll(pendientes);
                camion.clearDesvio();

                // Limpio también los backups
                camion.setRutaBackup(Collections.emptyList());
                camion.setPedidosBackup(Collections.emptyList());

                // 2) Si hay pendientes, genero de cero la ruta al siguiente pedido
                if (!pendientes.isEmpty()) {
                    Pedido siguiente = pendientes.get(0);
                    List<Point> ruta = buildManhattanPath(
                            camion.getX(), camion.getY(),
                            siguiente.getX(), siguiente.getY(),
                            tiempoActual
                    );
                    camion.setRutaActual(ruta);
                    camion.setPasoActual(0);
                    camion.getHistory().addAll(ruta);
                    int tt = (int) Math.ceil(ruta.size() * (60.0/50.0));
                    camion.setLibreEn(tiempoActual+15+tt);
                    camion.setStatus(CamionDinamico.TruckStatus.DELIVERING);
                    nuevosEventos.add(new EntregaEvent(tiempoActual + tt +15, camion, siguiente));

                }
            }
            // 3) Si no era desvío, iniciamos el retorno normal
            else {
                // 3.a) Si quedan pedidos pendientes, reprogramar el siguiente
                List<Pedido> pendientes = camion.getRutaPendiente();
                if (!pendientes.isEmpty()) {
                    // — programar el siguiente pendiente —
                    Pedido siguiente = pendientes.get(0);
                    List<Point> ruta = buildManhattanPath(
                            camion.getX(), camion.getY(),
                            siguiente.getX(), siguiente.getY(),
                            tiempoActual
                    );
                    camion.setRutaActual(ruta);
                    camion.setPasoActual(0);
                    camion.setStatus(CamionDinamico.TruckStatus.DELIVERING);
                    camion.getHistory().addAll(ruta);
                    int tt = (int) Math.ceil(ruta.size()*(60.0/50.0));
                    camion.setLibreEn(tiempoActual+15+tt);
                    nuevosEventos.add(new EntregaEvent(tiempoActual + tt +15, camion, siguiente));
                }
                // — SOLO si NO quedan pendientes, iniciamos retorno —
                if (pendientes.isEmpty()) {
                    System.out.printf("Invocando a startReturn%n");
                    startReturn(camion, tiempoActual, nuevosEventos);
                }
            }
        }

        // 4) Añadir todos los eventos programados durante el bucle
        estado.getEventosEntrega().addAll(nuevosEventos);
    }


    // helper: inicia retorno y programa el evento de llegada
    private void startReturn(CamionDinamico c, int tiempoActual, List<EntregaEvent> collector) {
        double falta = c.getCapacidad() - c.getDisponible();
        int sx = c.getX(), sy = c.getY();
        int dx = estado.getDepositoX(), dy = estado.getDepositoY();
        int distMin = Math.abs(sx - dx) + Math.abs(sy - dy);
        TanqueDinamico mejorT = null;
        for (TanqueDinamico t : estado.getTanques()) {
            if (t.getDisponible() >= falta) {
                int d = Math.abs(sx - t.getPosX()) + Math.abs(sy - t.getPosY());
                if (d < distMin) { distMin = d; mejorT = t; }
            }
        }
        c.setReabastecerEnTanque(mejorT);
        int destX = mejorT != null ? mejorT.getPosX() : dx;
        int destY = mejorT != null ? mejorT.getPosY() : dy;
        if (mejorT != null) {
            mejorT.setDisponible(mejorT.getDisponible() - falta);
            System.out.printf(
                    "🔁 t+%d: Tanque (%d,%d) reservado %.1fm³ → ahora %.1f m³%n",
                    tiempoActual, mejorT.getPosX(), mejorT.getPosY(),
                    falta, mejorT.getDisponible()
            );
        }

        c.setEnRetorno(true);
        c.setStatus(CamionDinamico.TruckStatus.RETURNING);

        List<Point> camino = buildManhattanPath(sx, sy, destX, destY, tiempoActual);
        c.setRutaActual(camino);
        c.setPasoActual(0);
        c.getHistory().addAll(camino);

        System.out.printf(
                "⏱️ t+%d: Camión %s inicia retorno a (%d,%d) dist=%d%n",
                tiempoActual, c.getId(), destX, destY, distMin
        );

        // programo el evento de llegada para el retorno
        int tt = (int) Math.ceil(camino.size() * (60.0/50.0));
        //c.setLibreEn(tiempoActual + tt + 15);  // tt minutos de viaje + 15 de recarga
        collector.add(new EntregaEvent(tiempoActual + tt, c, /*pedido=*/null));
    }

    // ------------------------------------------------------------
    // Métodos privados auxiliares copiados de ACOPlanner original
    // ------------------------------------------------------------
    private boolean esDesvioValido(CamionDinamico c, Pedido p, int tiempoActual) {
        // 🚫 Evitar desvío si está retornando a planta
        if (c.getStatus() == CamionDinamico.TruckStatus.RETURNING) {
            System.out.printf("❌ Camión %s está retornando a planta; no se permite desvío%n", c.getId());
            return false;
        }

        double disponible = c.getDisponible();
        int hora = tiempoActual;
        int prevX = c.getX(), prevY = c.getY();

        // — Primer tramo: al nuevo pedido —
        List<Point> pathToNew = buildManhattanPath(prevX, prevY, p.getX(), p.getY(), hora);
        if (pathToNew == null) return false;
        hora += pathToNew.size();
        hora += 15; // tiempo de servicio

        if (hora > p.getTiempoLimite()) {
            System.out.printf("❌ Pedido %d se pasaría del límite (%d > %d)%n", p.getId(), hora, p.getTiempoLimite());
            return false;
        }

        if (disponible < p.getVolumen()) {
            System.out.printf("❌ Camión %s no tiene volumen para Pedido %d (disp=%.1f, req=%.1f)%n",
                    c.getId(), p.getId(), disponible, p.getVolumen());
            return false;
        }

        disponible -= p.getVolumen();
        prevX = p.getX();
        prevY = p.getY();

        // — Evaluar los pedidos restantes —
        for (Pedido orig : c.getRutaPendiente()) {
            List<Point> pathSeg = buildManhattanPath(prevX, prevY, orig.getX(), orig.getY(), hora);
            if (pathSeg == null) return false;
            hora += pathSeg.size();
            hora += 15;

            if (hora > orig.getTiempoLimite()) return false;

            disponible -= orig.getVolumen();
            if (disponible < 0) {
                System.out.printf("❌ Camión %s se quedaría sin capacidad al llegar a Pedido %d (restante=%.1f)%n",
                        c.getId(), orig.getId(), disponible);
                return false;
            }

            prevX = orig.getX();
            prevY = orig.getY();
        }

        return true;
    }



    private int posicionOptimaDeInsercion(CamionDinamico c, Pedido pNuevo, int tiempoActual) {
        List<Pedido> originales = c.getRutaPendiente();
        int mejorIdx = originales.size();
        int mejorHoraEntrega = Integer.MAX_VALUE;

        // Capacidad y posición de arranque reales del camión
        double capacidadOriginal = c.getDisponible();
        int x0 = c.getX(), y0 = c.getY();

        // Probar cada posible posición de inserción
        for (int idx = 0; idx <= originales.size(); idx++) {
            double disponible = capacidadOriginal;
            int hora = tiempoActual;
            int simX = x0, simY = y0;

            // Montamos la lista de pedidos en el orden de prueba
            List<Pedido> prueba = new ArrayList<>(originales);
            prueba.add(idx, pNuevo);

            boolean valido = true;
            // Recorremos cada segmento (pedido) con ruta real
            for (Pedido q : prueba) {
                // 1) Construir la ruta real (bloqueos-aware) desde (simX,simY) hasta q
                List<Point> path = buildManhattanPath(simX, simY, q.getX(), q.getY(), hora);
                if (path == null) {
                    valido = false;
                    break;
                }
                // 2) Tiempo de viaje = número de pasos
                hora += path.size();
                // 3) Tiempo de servicio (descarga)
                hora += 15;
                // 4) Comprobar deadline
                if (hora > q.getTiempoLimite()) {
                    valido = false;
                    break;
                }
                // 5) Restar volumen al disponible
                disponible -= q.getVolumen();
                if (disponible < 0) {
                    valido = false;
                    break;
                }
                // 6) Avanzar “virtual” a la posición del pedido
                simX = q.getX();
                simY = q.getY();
            }

            // Si democrático y acaba antes (mejor horaEntrega), guardamos índice
            if (valido && hora < mejorHoraEntrega) {
                mejorHoraEntrega = hora;
                mejorIdx = idx;
            }
        }

        return mejorIdx;
    }

    private void aplicarRutas(int tiempoActual, List<Ruta> rutas, List<Pedido> activos) {
        rutas.removeIf(r -> r.pedidos == null || r.pedidos.isEmpty());
        if (rutas.isEmpty()) {
            System.out.printf("🚫 t+%d: Pedido(s) descartado(s) – no se generó ninguna ruta válida para %s%n",
                    tiempoActual,
                    activos.stream().map(p -> "#" + p.getId()).collect(Collectors.joining(", "))
            );
            return;
        }
        // A) Filtrar rutas que no caben en la flota real
        for (Iterator<Ruta> itR = rutas.iterator(); itR.hasNext(); ) {
            Ruta r = itR.next();
            CamionDinamico real = findCamion(r.estadoCamion.id);
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
            CamionDinamico camion = findCamion(ruta.estadoCamion.id);

            boolean wasReturning = camion.getStatus() == CamionDinamico.TruckStatus.RETURNING;
            if (!camion.getRutaActual().isEmpty()
                    && (camion.getStatus() == CamionDinamico.TruckStatus.DELIVERING
                    || wasReturning)) {

                for (int pedidoIdx : ruta.pedidos) {
                    Pedido nuevo = activos.get(pedidoIdx);
                    if (nuevo.isProgramado() || camion.getRutaPendiente().contains(nuevo))
                        continue; // evitar duplicados
                    System.out.printf("🔍 Evaluando desvío local: Camión=%s Pedido=%d | posiciónCamión=(%d,%d), posiciónPedido=(%d,%d), disponible=%.1f, status=%s%n",
                            camion.getId(), nuevo.getId(), camion.getX(), camion.getY(),
                            nuevo.getX(), nuevo.getY(), camion.getDisponible(), camion.getStatus());

                    boolean condValido = esDesvioValido(camion, nuevo, tiempoActual);
                    System.out.printf("   → esDesvioValido = %b%n", condValido);

                    if (condValido) {
                        int idx = posicionOptimaDeInsercion(camion, nuevo, tiempoActual);
                        camion.getRutaPendiente().add(idx, nuevo);
                        camion.setDisponible(camion.getDisponible() - nuevo.getVolumen());
                        nuevo.setProgramado(true);

                        System.out.printf("🔀 t+%d: Desvío – insertado Pedido #%d en %s en posición %d%n",
                                tiempoActual, nuevo.getId(), camion.getId(), idx);

                        // NOTA: No actualizamos rutaActual aquí (solo planificación),
                        // se mantiene la ruta actual hasta que termine el pedido anterior.
                    }
                }

            } else {
                // si venía retornando, cancelamos el regreso
                if (wasReturning) {
                    // 1) elimina el retorno programado
                    estado.getEventosEntrega()
                            .removeIf(ev -> ev.camion.equals(camion) && ev.pedido == null);

                    // 2) restaurar a disponible y limpiar rutas
                    camion.setEnRetorno(false);
                    camion.setStatus(CamionDinamico.TruckStatus.AVAILABLE);
                    camion.getRutaActual().clear();
                    camion.setPasoActual(0);
                    camion.getRutaPendiente().clear();
                    camion.setReabastecerEnTanque(null);  // limpia la referencia al tanque
                }
                camion.getRutaPendiente().clear();
                camion.setStatus(CamionDinamico.TruckStatus.DELIVERING);

                int cx = camion.getX(), cy = camion.getY();
                for (int pedidoIdx : ruta.pedidos) {
                    Pedido p = activos.get(pedidoIdx);
                    if (p.isProgramado() || p.isAtendido()) continue;
                    if (camion.getDisponible() < p.getVolumen()) {
                        System.out.printf("⚠ t+%d: Camión %s sin espacio para Pedido #%d%n",
                                tiempoActual, camion.getId(), p.getId());
                        continue;
                    }
                    System.out.printf("⏱️ t+%d: Asignando Pedido #%d al Camión %s%n (%d,%d)",
                            tiempoActual, p.getId(), camion.getId(),p.getX(), p.getY());

                    List<Point> path = buildManhattanPath(cx, cy, p.getX(), p.getY(), tiempoActual);
                    int dist = path.size();
                    int tViaje = (int) Math.ceil(dist * (60.0 / 50.0));

                    camion.setRutaActual(path);
                    camion.setPasoActual(0);
                    camion.getHistory().addAll(path);
                    p.setProgramado(true);
                    camion.getRutaPendiente().add(p);
                    estado.getEventosEntrega().add(new EntregaEvent(
                            tiempoActual + tViaje, camion, p
                    ));
                    System.out.printf("🕒 eventoEntrega programado t+%d → (%d,%d)%n",
                            tiempoActual + tViaje, p.getX(), p.getY());
                    Point last = path.get(path.size() - 1);
                    cx = last.x; cy = last.y;
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