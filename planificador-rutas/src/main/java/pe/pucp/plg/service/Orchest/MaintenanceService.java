package pe.pucp.plg.service.Orchest;

import java.awt.Point;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;

@Service
public class MaintenanceService {

    private final PathfindingService pathfindingService;
    private final FleetService fleetService;

    @Autowired
    public MaintenanceService(PathfindingService pathfindingService, FleetService fleetService) {
        this.pathfindingService = pathfindingService;
        this.fleetService = fleetService;
    }
    
    public void clearMaintenance(ExecutionContext contexto) {
        // Primero cleareamos los mantenimientos
        for(CamionEstado c : contexto.getCamiones()) {
            if(c.getStatus() == CamionEstado.TruckStatus.MAINTENANCE) {
                c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
            }
        }
    }

    public void processMaintenances(ExecutionContext contexto, LocalDateTime tiempoActual) {
        if((tiempoActual.getHour() == 0 && tiempoActual.getMinute() == 0) || tiempoActual.minusMinutes(1).equals(contexto.getFechaInicio().atStartOfDay())) {
            // Lógica de mantenimientos
            LocalDate fechaActual = tiempoActual.toLocalDate();
            String camionIdMantenimiento = contexto.getMantenimientos().get(fechaActual);
            if(camionIdMantenimiento != null) {
                CamionEstado camionParaMantenimiento = fleetService.findCamion(camionIdMantenimiento, contexto);
                if (camionParaMantenimiento != null) {
                    TanqueDinamico planta = contexto.getTanques().get(0);
                    if(camionParaMantenimiento.getStatus() == CamionEstado.TruckStatus.AVAILABLE) { // Esto quiere decir que está en un tanque o planta
                        TanqueDinamico tanque = camionParaMantenimiento.getTanqueDestinoRecarga();
                        if(tanque != null) {
                            if(!tanque.equals(contexto.getTanques().get(0))) { // Si es el primer tanque, es que está en el depósito
                                List<Point> camino = pathfindingService.buildManhattanPath(camionParaMantenimiento.getX(), camionParaMantenimiento.getY(), planta.getPosX(), planta.getPosY(), tiempoActual, contexto);
                                camionParaMantenimiento.setRuta(camino);
                                camionParaMantenimiento.setPasoActual(0);
                            }
                            camionParaMantenimiento.setTiempoLibre(tiempoActual.plusHours(24));
                        }
                    } else if(!(camionParaMantenimiento.getStatus() == CamionEstado.TruckStatus.BREAKDOWN)) {
                        List<Point> camino = pathfindingService.buildManhattanPath(camionParaMantenimiento.getX(), camionParaMantenimiento.getY(), planta.getPosX(), planta.getPosY(), tiempoActual, contexto);
                        camionParaMantenimiento.setRuta(camino);
                        camionParaMantenimiento.setPasoActual(0);
                        camionParaMantenimiento.setTiempoLibre(tiempoActual.plusHours(24));
                    }
                    for (Pedido pPend : new ArrayList<>(camionParaMantenimiento.getPedidosCargados())) {
                        pPend.setProgramado(false); // volver a la cola de planificación
                        pPend.setHoraEntregaProgramada(null);
                        pPend.setAtendido(false); 
                    }
                    // Limpieza total de datos de rutas y pedidos para el camión averiado
                    camionParaMantenimiento.getPedidosCargados().clear();  // Limpiar pedidos cargados
                    camionParaMantenimiento.setRuta(Collections.emptyList());  // Limpiar ruta actual
                    camionParaMantenimiento.setPasoActual(0);  // Resetear paso actual
                    camionParaMantenimiento.getHistory().clear();  // Limpiar historial de movimientos
                    camionParaMantenimiento.setStatus(CamionEstado.TruckStatus.MAINTENANCE);
                }
            }
        }
    }



}
