package pe.pucp.plg.service.Orchest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;
import pe.pucp.plg.util.ResourceLoader;

@Service
public class SimulationStateService {

    private final MaintenanceService maintenanceService;

    @Autowired
    public SimulationStateService(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }
    
    public void accionesMediaNoche(ExecutionContext contexto, LocalDateTime tiempoActual, boolean replanificar) {
        for (TanqueDinamico tq : contexto.getTanques()) {
            tq.setDisponible(tq.getCapacidadTotal());
        }

        // Reiniciar el estado de las averías de archivo para que puedan repetirse diariamente
        contexto.getAveriasAplicadas().clear();
        contexto.getPuntosAveria().clear();

        contexto.getBloqueosPorDia().removeIf(b -> b.getEndTime().isBefore(tiempoActual));
        
        // Determinar qué día estamos y cargar datos para ese día
        LocalDate fechaActual = tiempoActual.toLocalDate();
        long diaActual = fechaActual.toEpochDay() - contexto.getFechaInicio().toEpochDay() + 1;
        
        // Solo cargamos datos nuevos si estamos dentro del período de simulación
        if (diaActual <= contexto.getDuracionDias()) {
            
            // Cargar pedidos y bloqueos para este día
            List<Pedido> nuevoPedidos = ResourceLoader.cargarPedidosParaFecha(fechaActual);
            List<Bloqueo> nuevoBloqueos = ResourceLoader.cargarBloqueosParaFecha(fechaActual);

            // Añadir los nuevos pedidos al mapa de pedidos por tiempo
            for (Pedido p : nuevoPedidos) {
                contexto.getPedidosPorTiempo().computeIfAbsent(p.getTiempoCreacion(), k -> new ArrayList<>()).add(p);
            }
            
            // Añadir los nuevos bloqueos
            for (Bloqueo b : nuevoBloqueos) {
                contexto.getBloqueosPorTiempo().computeIfAbsent(b.getStartTime(), k -> new ArrayList<>()).add(b);
                contexto.getBloqueosPorDia().add(b);
            }
            
            // Si hay nuevos datos, replanificar
            if (!nuevoPedidos.isEmpty() || !nuevoBloqueos.isEmpty()) {
                replanificar = true;
            }
        }
        maintenanceService.clearMaintenance(contexto);
    }

    public void inyectarPedidos(ExecutionContext contexto, LocalDateTime tiempoActual, boolean replanificar) {
        // 5) Incorporar nuevos pedidos que llegan en este minuto
        List<Pedido> nuevos = contexto.getPedidosPorTiempo().remove(tiempoActual);
        if (nuevos == null) {
            nuevos = Collections.emptyList();
        }

        // 5.a) Calcular capacidad máxima de un camión (suponiendo que todos tienen la misma capacidad)
        /*double capacidadMaxCamion = contexto.getCamiones().stream()
                .mapToDouble(c -> c.getPlantilla().getCapacidadCarga())
                .max()
                .orElse(0);*/
        double capacidadMaxCamion = 10.0;

        List<Pedido> pedidosAInyectar = new ArrayList<>();
        for (Pedido p : nuevos) {
            double volumenRestante = p.getVolumen();

            if (volumenRestante > capacidadMaxCamion) {
                // 🛠️ Dividir en sub-pedidos de ≤ capacidadMaxCamion
                while (volumenRestante > 0) {
                    double vol = Math.min(capacidadMaxCamion, volumenRestante);
                    int subId = contexto.generateUniquePedidoId();
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
        contexto.getPedidos().addAll(pedidosAInyectar);

        if (!pedidosAInyectar.isEmpty()) replanificar = true;
        
    }

    public boolean hayColapso(ExecutionContext contexto, LocalDateTime tiempoActual) {
        // ----------------------------------------------
        // 6) Comprobar colapso: pedidos vencidos
        Iterator<Pedido> itP = contexto.getPedidos().iterator();
        boolean haColapsado = false;
        while (itP.hasNext()) {
            Pedido p = itP.next();
            if (!p.isAtendido() && !p.isDescartado() && !p.isEnEntrega() && (tiempoActual.isAfter(p.getTiempoLimite().plusMinutes(2)) || tiempoActual.isAfter(p.getTiempoLimite()))) {
                /*System.out.printf("💥 Colapso en t+%d, pedido %d incumplido%n",
                        tiempoActual, p.getId());*/
                // Marca y elimina para no repetir el colapso
                contexto.setPedidoColapso(p.getId() + " (" + p.getX() + "," + p.getY() + ")");
                p.setDescartado(true);
                itP.remove();
                haColapsado = true;
            }
        }
        return haColapsado;
        
    }

    public void actualizarPedidosPorTanque(ExecutionContext contexto) {
        // Mapa para agregar pedidos por ID de tanque.
        Map<String, List<String>> pedidosPorTanqueId = new HashMap<>();

        // 1. Recorre TODOS los camiones y AGREGA sus pedidos al mapa.
        for (CamionEstado camion : contexto.getCamiones()) {
            if (camion.getTanqueOrigen() != null && (!camion.getPedidosCargados().isEmpty() || camion.getPedidoDesvio() != null)) {
                String tanqueId = camion.getTanqueOrigen().getId();

                // Asegúrate de que haya una lista para este tanque.
                pedidosPorTanqueId.putIfAbsent(tanqueId, new ArrayList<>());

                // Agrega los pedidos cargados.
                if(camion.getPedidosCargados() != null) {
                    for (Pedido p : camion.getPedidosCargados()) {
                        pedidosPorTanqueId.get(tanqueId).add(String.valueOf(p.getId()));
                    }
                }

                if(camion.getPedidosBackup() != null) {
                    for (Pedido p : camion.getPedidosBackup()) {
                        pedidosPorTanqueId.get(tanqueId).add(String.valueOf(p.getId()));
                    }
                }

                // Agrega el pedido de desvío si existe.
                if (camion.getPedidoDesvio() != null) {
                    pedidosPorTanqueId.get(tanqueId).add(String.valueOf(camion.getPedidoDesvio().getId()));
                }
            }
        }

        // 2. Ahora, recorre los tanques y actualízalos con la lista COMPLETA.
        for (TanqueDinamico tanque : contexto.getTanques()) {
            // Obtiene la lista de pedidos para este tanque (o una lista vacía si no tiene).
            List<String> pedidosAgregados = pedidosPorTanqueId.getOrDefault(tanque.getId(), Collections.emptyList());

            // Elimina duplicados por si acaso y actualiza.
            tanque.setPedidos(pedidosAgregados.stream().distinct().collect(Collectors.toList()));
        }
    }

}
