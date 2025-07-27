package pe.pucp.plg.service.Orchest;

import java.awt.Point;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import pe.pucp.plg.model.common.EntregaEvent;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;

@Service
public class DeliveryEventService {

    private final int TIEMPO_SERVICIO = 15;
    private final PathfindingService pathfindingService;
    private final FleetService fleetService;

    @Autowired
    public DeliveryEventService(PathfindingService pathfindingService, FleetService fleetService) {
        this.pathfindingService = pathfindingService;
        this.fleetService = fleetService;
    }
    
     // 2) Disparar eventos de entrega programados para este minuto
    public void triggerScheduledDeliveries(LocalDateTime tiempoActual, ExecutionContext contexto) {

        List<EntregaEvent> nuevosEventos = new ArrayList<>();

        // Mientras haya eventos en la cola Y el siguiente evento sea para AHORA
        while (!contexto.getEventosEntrega().isEmpty() && 
            !contexto.getEventosEntrega().peek().time.isAfter(tiempoActual)) {

            // Saca el evento de la cola
            EntregaEvent ev = contexto.getEventosEntrega().poll();
            // ——— 2) Log de procesamiento de cada evento ——————————
            System.out.printf(
                "⌛ Procesando EntregaEvent → camión=%s pedido=%s programado para %s%n",
                ev.getCamionId(),
                ev.getPedido() != null ? ev.getPedido().getId() : "RETORNO",
                ev.time
            );
            // Compara el valor del tiempo, no la referencia del objeto
            if (!ev.time.equals(tiempoActual)) {
                continue;
            }

            CamionEstado camion = fleetService.findCamion(ev.getCamionId(), contexto);
            Pedido pedido = ev.getPedido();
        
            // Si el camión está averiado, ignorar cualquier evento programado
            if (camion != null && (camion.getStatus() == CamionEstado.TruckStatus.BREAKDOWN || camion.getStatus() == CamionEstado.TruckStatus.MAINTENANCE)) {
                continue;
            }

            // CASO A: El evento es un retorno a la planta (no hay pedido)
            if (pedido == null) {
                startReturn(camion, tiempoActual, nuevosEventos, contexto);
                continue;
            }

            if(camion == null) {
                continue;
            }

            // CASO B: El evento es una LLEGADA al cliente
            if (camion.getStatus() == CamionEstado.TruckStatus.DELIVERING) {
                System.out.printf("➡️  LLEGADA: Camión %s llegó a pedido %d en %s. Inicia servicio.%n", camion.getPlantilla().getId(), pedido.getId(), tiempoActual);
                
                camion.setX(pedido.getX());
                camion.setY(pedido.getY());
                camion.setStatus(CamionEstado.TruckStatus.UNAVAILABLE); // Ocupado durante el servicio
                pedido.setEnEntrega(true);
                
                // Se agenda el evento de "Fin de Servicio" para más tarde
                LocalDateTime finServicio = tiempoActual.plusMinutes(TIEMPO_SERVICIO);
                camion.setTiempoLibre(finServicio);
                nuevosEventos.add(new EntregaEvent(finServicio, camion.getPlantilla().getId(), pedido));
            } 
            // CASO C: El evento es un FIN DE SERVICIO
            else if (camion.getStatus() == CamionEstado.TruckStatus.UNAVAILABLE) {
                // ——— 3) Log de liberación —————————————
                System.out.printf(
                    "✅ Entrega evento → camión=%s pedido=%s liberado en t=%s%n", camion.getPlantilla().getId(),
                    pedido.getId(),
                    tiempoActual
                );
                System.out.printf("✅ FIN SERVICIO: Camión %s completó pedido %d en %s.%n", camion.getPlantilla().getId(), pedido.getId(), tiempoActual);
                
                double antes = camion.getCapacidadDisponible();
                camion.setCapacidadDisponible(antes - pedido.getVolumen());
                pedido.setAtendido(true); 
                contexto.setTotalPedidosEntregados(contexto.getTotalPedidosEntregados() + 1);
                // Eliminar pedido de la lista de pedidos
                contexto.getPedidos().remove(pedido);
                camion.clearDesvio();
                camion.setStatus(CamionEstado.TruckStatus.AVAILABLE); // Vuelve a estar disponible
                
                camion.getPedidosCargados().removeIf(p -> p.getId() == pedido.getId());

                // Después de entregar, decide si sigue con su ruta o vuelve a la base
                if (!camion.getPedidosCargados().isEmpty() && (camion.getStatus() != CamionEstado.TruckStatus.BREAKDOWN && camion.getStatus() != CamionEstado.TruckStatus.MAINTENANCE)) {
                    Pedido siguiente = camion.getPedidosCargados().get(0);
                    List<Point> ruta = pathfindingService.buildManhattanPath(
                            camion.getX(), camion.getY(),
                            siguiente.getX(), siguiente.getY(),
                            tiempoActual,
                            contexto
                    );
                    //int tt = (int) Math.ceil(ruta.size() * (60.0/50.0));
                    int tt = ruta.size(); 
                    camion.setRuta(ruta);
                    camion.setPasoActual(0);
                    camion.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    camion.setTiempoLibre(tiempoActual.plusMinutes(tt + TIEMPO_SERVICIO));
                    nuevosEventos.add(new EntregaEvent(tiempoActual.plusMinutes(tt), camion.getPlantilla().getId(), siguiente));
                } else {
                    startReturn(camion, tiempoActual, nuevosEventos, contexto);
                }
            } else {
                // continuar con la ruta pendiente
                if (!camion.getPedidosCargados().isEmpty() && (camion.getStatus() != CamionEstado.TruckStatus.BREAKDOWN && camion.getStatus() != CamionEstado.TruckStatus.MAINTENANCE)) {
                    Pedido siguiente = camion.getPedidosCargados().get(0);
                    List<Point> ruta = pathfindingService.buildManhattanPath(
                            camion.getX(), camion.getY(),
                            siguiente.getX(), siguiente.getY(),
                            tiempoActual,
                            contexto
                    );
                    camion.setRuta(ruta);
                    camion.setPasoActual(0);
                    camion.setStatus(CamionEstado.TruckStatus.DELIVERING);
                    //camion.getHistory().addAll(ruta);
                    //int tt = (int) Math.ceil(ruta.size() * (60.0/50.0));
                    int tt = ruta.size();
                    camion.setTiempoLibre(tiempoActual.plusMinutes(tt + TIEMPO_SERVICIO));
                    nuevosEventos.add(new EntregaEvent(tiempoActual.plusMinutes(tt), camion.getPlantilla().getId(), siguiente));
                } else {
                    startReturn(camion, tiempoActual, nuevosEventos, contexto);
                }
            }
        }

        // añadir todos los eventos recién creados
        contexto.getEventosEntrega().addAll(nuevosEventos);
    }

    // helper: inicia retorno y programa el evento de llegada
    private void startReturn(CamionEstado c, LocalDateTime tiempoActual, List<EntregaEvent> collector, ExecutionContext contexto) {
        double falta = c.getPlantilla().getCapacidadCarga() - c.getCapacidadDisponible();
        int sx = c.getX(), sy = c.getY();
        int dx = contexto.getDepositoX(), dy = contexto.getDepositoY();
        int distMin = Math.abs(sx - dx) + Math.abs(sy - dy);
        TanqueDinamico mejorT = null;
        for (TanqueDinamico t : contexto.getTanques()) {
            if (t.getDisponible() >= falta) {
                int d = Math.abs(sx - t.getPosX()) + Math.abs(sy - t.getPosY());
                if (d < distMin) { distMin = d; mejorT = t; }
            }
        }
        
        int destX = mejorT != null ? mejorT.getPosX() : dx;
        int destY = mejorT != null ? mejorT.getPosY() : dy;
        if (mejorT != null) {
            mejorT.setDisponible(mejorT.getDisponible() - falta);
        }
        c.setStatus(CamionEstado.TruckStatus.RETURNING);

        List<Point> camino = pathfindingService.buildManhattanPath(sx, sy, destX, destY, tiempoActual, contexto);

        if (camino == null || camino.isEmpty()) {
            System.err.printf(
                    "⚠️ t+%s: Camión %s no puede retornar a (%d,%d). No se encontró ruta. Permanece AVAILABLE en (%d,%d).%n",
                    tiempoActual, c.getPlantilla().getId(), destX, destY, c.getX(), c.getY());
            return;
        }
        c.setRuta(camino);
        c.setPasoActual(0);
        //c.getHistory().addAll(camino);

    }

    
}
