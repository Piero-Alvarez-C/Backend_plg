package pe.pucp.plg.service.algorithm;

import org.springframework.stereotype.Service;

import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ACOPlanner {

    private static final int ITERACIONES = 50; // ajustar según tus pruebas
    private static final int HORMIGAS = 30;    // idem
    private static final double ALPHA = 1.0;
    private static final double BETA = 2.0;
    private static final double RHO = 0.1;     // evaporación
    private static final double Q = 100.0;     // feromona depositada

    public List<Ruta> planificarRutas(List<Pedido> candidatos, List<CamionEstado> flotaParaPlanificar, int tiempoActual, ExecutionContext contexto) {
        if (candidatos.isEmpty() || flotaParaPlanificar.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Integer> idsCandidatos = candidatos.stream()
                .map(Pedido::getId)
                .collect(Collectors.toSet());
        contexto.getEventosEntrega().removeIf(e -> idsCandidatos.contains(e.getPedido().getId()));
        for(Pedido p : candidatos) {
            p.setProgramado(false);
        }

        // 3. Ejecutar ACO con los clones y candidatos.
        return ejecutarACO(candidatos, flotaParaPlanificar, tiempoActual);
    }

    // ------------------------------------------------------------
    // 1) Ejecución del algoritmo ACO para el VRP
    // ------------------------------------------------------------
    private List<Ruta> ejecutarACO(List<Pedido> pedidosActivos, List<CamionEstado> flotaEstado, int tiempoActual) {
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
                    double[][] prob = calcularProbabilidades(rutas, clonedFlota, pedidosActivos, noAsignados, tau, tiempoActual);

                    Seleccion sel = muestrearPar(prob, noAsignados);
                    if (sel == null) break; // No more valid assignments

                    asignarPedidoARuta(sel.camionIdx, sel.pedidoIdx, rutas, clonedFlota, pedidosActivos, tiempoActual);
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
                idToIndex.put(flotaEstado.get(i).getPlantilla().getId(), i);
            }
            for (List<Ruta> sol : soluciones) {
                double coste = calcularCosteTotal(sol);
                if (coste < mejorCoste) {
                    mejorCoste = coste;
                    mejorSol = sol;
                }
                for (Ruta ruta : sol) {
                    int v = idToIndex.getOrDefault(ruta.getCamionId(), -1);
                    if (v >= 0) {
                        for (int idx : ruta.getPedidoIds()) {
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
        return original.stream()
                .map(CamionEstado::new) // Llama al constructor de copia: new CamionEstado(original)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------
    // 3) Inicializar rutas vacías (una por camión)
    // ------------------------------------------------------------
    private List<Ruta> initRutas(List<CamionEstado> flota) {
        List<Ruta> rutas = new ArrayList<>();
        for (CamionEstado est : flota) {
            Ruta r = new Ruta(est.getPlantilla().getId());
            rutas.add(r);
        }
        return rutas;
    }

    // ------------------------------------------------------------
    // 4) Calcular probabilidades (feromonas + heurística)
    // ------------------------------------------------------------
    private double[][] calcularProbabilidades(
            List<Ruta> rutas,
            List<CamionEstado> flotaClonada,
            List<Pedido> pedidosActivos,
            List<Integer> noAsignados,
            double[][] tau,
            int tiempoActual) {

        int V = rutas.size();
        double[][] prob = new double[V][pedidosActivos.size()];
        double minPorKm = 60.0 / 50.0;

        for (int v = 0; v < V; v++) {
            CamionEstado c = findCamionInList(rutas.get(v).getCamionId(), flotaClonada);
            if (c == null) continue;

            for (int idx : noAsignados) {
                Pedido p = pedidosActivos.get(idx);

                // 1) filtro capacidad
                if (c.getCapacidadDisponible() < p.getVolumen()) continue;

                // 2) filtro ventana de tiempo
                int dx = Math.abs(c.getX() - p.getX());
                int dy = Math.abs(c.getY() - p.getY());
                int distKm = dx + dy;
                int tiempoViaje = (int) Math.ceil(distKm * minPorKm);
                if (tiempoActual + tiempoViaje > p.getTiempoLimite()) continue;

                // 3) filtro combustible
                double pesoCargaTon = p.getVolumen() * 0.5;
                double pesoTaraTon  = c.getPlantilla().getTara() / 1000.0;
                double pesoTotalTon = pesoCargaTon + pesoTaraTon;
                double galNecesarios = distKm * pesoTotalTon / 180.0;
                if (c.getCombustibleActual() < galNecesarios) continue;

                // heurística + feromona
                double penalTiempo = 1.0 / (1 + Math.max(0, c.getTiempoLibre() - tiempoActual));
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
            List<CamionEstado> flotaClonada,
            List<Pedido> pedidosActivos,
            int tiempoActual) {

        Ruta ruta = rutas.get(camionIdx);
        CamionEstado c = findCamionInList(ruta.getCamionId(), flotaClonada);
        if (c == null) return false;
        Pedido p   = pedidosActivos.get(pedidoIdx);

        if (ruta.getPedidoIds().contains(pedidoIdx)) return false;
        if (c.getCapacidadDisponible() < p.getVolumen()) return false;

        int dx = Math.abs(c.getX() - p.getX());
        int dy = Math.abs(c.getY() - p.getY());
        int distKm  = dx + dy;
        double minPorKm = 60.0 / 50.0;
        int tiempoViaje = (int) Math.ceil(distKm * minPorKm);

        if (tiempoActual + tiempoViaje > p.getTiempoLimite()) return false;

        double pesoCargaTon = p.getVolumen() * 0.5;
        double pesoTaraTon  = c.getPlantilla().getTara() / 1000.0;
        double pesoTotalTon = pesoTaraTon + pesoCargaTon;
        double galNecesarios = distKm * pesoTotalTon / 180.0;
        if (c.getCombustibleActual() < galNecesarios) return false;

        // actualizar estado camión CLONADO
        c.setTiempoLibre(tiempoActual + tiempoViaje + 15); // +15 min descarga
        c.setX(p.getX());
        c.setY(p.getY());

        double nuevaCapacidad = c.getCapacidadDisponible() - p.getVolumen();
        if (nuevaCapacidad < 0) return false;
        c.setCapacidadDisponible(nuevaCapacidad);

        ruta.distancia      += distKm;
        ruta.consumo        += galNecesarios;
        c.setCombustibleDisponible(c.getCombustibleActual() - galNecesarios);

        ruta.getPedidoIds().add(pedidoIdx);
        return true;
    }

    // ------------------------------------------------------------
    // 7) Costo total de las rutas (suma de consumos)
    // ------------------------------------------------------------
    private double calcularCosteTotal(List<Ruta> sol) {
        return sol.stream().mapToDouble(r -> r.consumo).sum();
    }

    // ------------------------------------------------------------
    // 8) Buscar un camión en una flota por su id
    // ------------------------------------------------------------
    private CamionEstado findCamionInList(String id, List<CamionEstado> flota) {
        return flota.stream()
                .filter(c -> c.getPlantilla().getId().equals(id))
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



}