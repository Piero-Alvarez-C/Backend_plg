package pe.pucp.plg.service.Orchest;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;

@Service
public class FleetService {
    
    private final int TIEMPO_SERVICIO = 15;
    private static final double MINUTOS_POR_PASO = 1;

    public CamionEstado findCamion(String camionId, ExecutionContext estado) {
        return estado.getCamiones().stream()
                .filter(c -> c.getPlantilla().getId().equals(camionId))
                .findFirst().orElse(null);
    }

    public void avanzar(ExecutionContext contexto, LocalDateTime tiempoActual) {
        for (CamionEstado c : contexto.getCamiones()) {
            if (c.getStatus() == CamionEstado.TruckStatus.BREAKDOWN || 
                c.getStatus() == CamionEstado.TruckStatus.MAINTENANCE || 
                c.getStatus() == CamionEstado.TruckStatus.UNAVAILABLE) {
                continue;
            }

            // Lógica de movimiento para DELIVERING y RETURNING
            if ((c.getStatus() == CamionEstado.TruckStatus.DELIVERING || c.getStatus() == CamionEstado.TruckStatus.RETURNING) && c.tienePasosPendientes()) {
                
                // El camión acumula 1 minuto de "esfuerzo" de movimiento
                c.setProgresoMovimiento(c.getProgresoMovimiento() + 1.0);

                // Solo si ha acumulado suficiente "esfuerzo" (1.2 min), avanza un paso
                if (c.getProgresoMovimiento() >= MINUTOS_POR_PASO) {
                    c.avanzarUnPaso(); // Mueve el camión 1 km
                    contexto.setTotalDistanciaRecorrida(contexto.getTotalDistanciaRecorrida() + 1);
                    
                    // Se resta el costo del movimiento, guardando el progreso sobrante.
                    c.setProgresoMovimiento(c.getProgresoMovimiento() - MINUTOS_POR_PASO);
                }
            }

            if (c.getStatus() == CamionEstado.TruckStatus.RETURNING && !c.tienePasosPendientes()) {
                c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                c.setCapacidadDisponible(c.getPlantilla().getCapacidadCarga());
                c.setCombustibleDisponible(c.getPlantilla().getCapacidadCombustible()); 
                c.setTiempoLibre(tiempoActual.plusMinutes(TIEMPO_SERVICIO));
                c.getPedidosCargados().clear();
                c.setProgresoMovimiento(0.0); // Resetea el progreso al llegar

                // Identifica el tanque de llegada y lo establece como el nuevo posible origen
                for(TanqueDinamico t : contexto.getTanques()) {
                    if (t.getPosX() == c.getX() && t.getPosY() == c.getY()) {
                        if(t.equals(contexto.getTanques().get(0))) { // Si llega a la planta principal
                            c.setTiempoLibre(tiempoActual); // Puede que no necesite tiempo de recarga
                        }
                        c.setTanqueOrigen(t);
                        break;
                    }
                }
            }

        }
    }

    

}