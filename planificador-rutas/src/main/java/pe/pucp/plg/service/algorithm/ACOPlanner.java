package pe.pucp.plg.service.algorithm;

import org.springframework.stereotype.Service;

import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ACOPlanner {

    private static final int ITERACIONES = 100; // ajustar según tus pruebas
    private static final int HORMIGAS = 50;    // idem
    private static final double ALPHA = 1.0;
    private static final double BETA = 1.5;
    private static final double BETA_URGENCIA = 5.0; 
    private static final double RHO = 0.1;     // evaporación
    private static final double Q = 100.0;     // feromona depositada

    public List<Ruta> planificarRutas(List<Pedido> candidatos, List<CamionEstado> flotaParaPlanificar, LocalDateTime tiempoActual, ExecutionContext contexto) {
        if (candidatos.isEmpty() || flotaParaPlanificar.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> idsCandidatos = candidatos.stream()
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
    private List<Ruta> ejecutarACO(List<Pedido> pedidosActivos, List<CamionEstado> flotaEstado, LocalDateTime tiempoActual) {
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
                    //if (sel == null) break; // No more valid assignments

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
                double coste = calcularCosteTotal(sol, pedidosActivos, flotaEstado, tiempoActual); 
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

        if (mejorSol == null || mejorSol.isEmpty()) {
            System.out.printf("⚠️ [ACO] No pudo generar solución para pedidos: %s%n",
                    pedidosActivos.stream()
                            .map(p -> "#" + p.getId())
                            .collect(Collectors.joining(", "))
            );
            // aquí puedes optar por devolver Collections.emptyList()
            // o retornar directamente mejorSol (que es null/empty)
            return Collections.emptyList();
        }
        
        System.out.println("Coste para mejor solucion: " + mejorCoste);
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
            LocalDateTime tiempoActual) {

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
                LocalDateTime tiempoEstimadoLlegada = tiempoActual.plusMinutes(tiempoViaje);
                if (tiempoEstimadoLlegada.isAfter(p.getTiempoLimite())) continue;

                // 3) filtro combustible
                double pesoCargaTon = p.getVolumen() * 0.5;
                double pesoTaraTon  = c.getPlantilla().getTara() / 1000.0;
                double pesoTotalTon = pesoCargaTon + pesoTaraTon;
                double galNecesarios = distKm * pesoTotalTon / 180.0;
                if (c.getCombustibleActual() < galNecesarios) continue;

                // 1. Heurística de Distancia (la que ya tenías)
                double heuristicaDistancia = 1.0 / (distKm + 1);

                // 2. Heurística de Urgencia (NUEVA)
                long laxitudEnMinutos = ChronoUnit.MINUTES.between(tiempoEstimadoLlegada, p.getTiempoLimite());
                // Si la laxitud es negativa o cero, el pedido es extremadamente urgente.
                // Le damos un valor mínimo de 1 para evitar división por cero.
                if (laxitudEnMinutos < 1) {
                    laxitudEnMinutos = 1;
                }
                double heuristicaUrgencia = 1.0 / laxitudEnMinutos;

                // 3. Combinar las heurísticas
                // La disponibilidad del camión sigue siendo importante.
                long minutesDiff = 0;
                if (c.getTiempoLibre() != null) {
                    minutesDiff = Math.max(0, ChronoUnit.MINUTES.between(tiempoActual, c.getTiempoLibre()));
                }
                double penalTiempoCamion = 1.0 / (1 + minutesDiff);
                
                double eta = Math.pow(heuristicaDistancia * penalTiempoCamion, BETA) * Math.pow(heuristicaUrgencia, BETA_URGENCIA);
                
                prob[v][idx] = Math.pow(tau[v][idx], ALPHA) * eta;
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
            LocalDateTime tiempoActual) {

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

        LocalDateTime tiempoEstimadoLlegada = tiempoActual.plusMinutes(tiempoViaje);
        if (tiempoEstimadoLlegada.isAfter(p.getTiempoLimite())) return false;

        double pesoCargaTon = p.getVolumen() * 0.5;
        double pesoTaraTon  = c.getPlantilla().getTara() / 1000.0;
        double pesoTotalTon = pesoTaraTon + pesoCargaTon;
        double galNecesarios = distKm * pesoTotalTon / 180.0;
        if (c.getCombustibleActual() < galNecesarios) return false;

        // actualizar estado camión CLONADO
        c.setTiempoLibre(tiempoActual.plusMinutes(tiempoViaje + 15)); // +15 min descarga
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
    private double calcularCosteTotal(List<Ruta> sol, List<Pedido> pedidosActivos, List<CamionEstado> flota, LocalDateTime tiempoActual) {
        double costeConsumo = sol.stream().mapToDouble(r -> r.consumo).sum();
        double costeTardanza = 0;
        double costeRiesgo = 0; 
        double costePorAdelantarse = 0;

        // ¡Un valor muy alto para que domine!
        final double PENALTY_POR_MINUTO_TARDE = 1000.0; 
        // Una penalización significativa, pero menor que la de tardanza.
        final double PENALTY_POR_BAJA_LAXITUD = 50.0; 
        final double PENALTY_POR_ENTREGA_TEMPRANA = 0.1;

        List<CamionEstado> flotaSimulada = deepCopyFlota(flota);

        Set<Integer> indicesAsignados = new HashSet<>();
        for (Ruta ruta : sol) {
            for (int pedidoIdx : ruta.getPedidoIds()) {
                indicesAsignados.add(pedidoIdx);
            }
        }

        for (Ruta ruta : sol) {
            CamionEstado camionSimulado = findCamionInList(ruta.getCamionId(), flotaSimulada);
            if (camionSimulado == null) continue;
            
            LocalDateTime tiempoSimulado = tiempoActual;
            double minPorKm = 60.0 / 50.0;

            for (int pedidoIdx : ruta.getPedidoIds()) {
                Pedido pedido = pedidosActivos.get(pedidoIdx);
                
                int dist = Math.abs(camionSimulado.getX() - pedido.getX()) + Math.abs(camionSimulado.getY() - pedido.getY());
                int tiempoViaje = (int) Math.ceil(dist * minPorKm);
                
                tiempoSimulado = tiempoSimulado.plusMinutes(tiempoViaje);
                
                if (tiempoSimulado.isAfter(pedido.getTiempoLimite())) {
                    // Penalización por tardanza (esto se mantiene igual)
                    long minutosTarde = ChronoUnit.MINUTES.between(pedido.getTiempoLimite(), tiempoSimulado);
                    costeTardanza += minutosTarde * PENALTY_POR_MINUTO_TARDE;
                } else {
                    long laxitudEnMinutos = ChronoUnit.MINUTES.between(tiempoSimulado, pedido.getTiempoLimite());
                    costeRiesgo += PENALTY_POR_BAJA_LAXITUD / (laxitudEnMinutos + 1.0);
                    if (laxitudEnMinutos > 720) { 
                        costePorAdelantarse += (laxitudEnMinutos - 720) * PENALTY_POR_ENTREGA_TEMPRANA;
                    }
                }

                // Actualizar para el siguiente tramo
                camionSimulado.setX(pedido.getX());
                camionSimulado.setY(pedido.getY());
                tiempoSimulado = tiempoSimulado.plusMinutes(15); // Tiempo de servicio
            }
        }
        double costePorAbandono = 0;
        final double PENALTY_POR_PEDIDO_URGENTE_NO_ASIGNADO = 100000.0; 

        // Iteramos sobre todos los pedidos que debían ser planificados
        for (int i = 0; i < pedidosActivos.size(); i++) {
            // Si el índice de este pedido NO está en el set de asignados...
            if (!indicesAsignados.contains(i)) {
                Pedido pedidoNoAsignado = pedidosActivos.get(i);
                
                // Calculamos cuánto tiempo le queda
                long minutosRestantes = ChronoUnit.MINUTES.between(tiempoActual, pedidoNoAsignado.getTiempoLimite());

                // Si le quedan menos de 7 horas (420 minutos), aplicamos la penalización
                if (minutosRestantes < 420) {
                    costePorAbandono += PENALTY_POR_PEDIDO_URGENTE_NO_ASIGNADO;
                }
            }
        }

        return costeConsumo + costeTardanza + costePorAbandono + costeRiesgo + costePorAdelantarse;
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