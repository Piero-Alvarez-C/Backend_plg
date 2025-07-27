package pe.pucp.plg.service.Orchest;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;

@Service
public class FleetService {
    
    private final int TIEMPO_SERVICIO = 15;

    public CamionEstado findCamion(String camionId, ExecutionContext estado) {
        return estado.getCamiones().stream()
                .filter(c -> c.getPlantilla().getId().equals(camionId))
                .findFirst().orElse(null);
    }

    public void avanzar(ExecutionContext contexto, LocalDateTime tiempoActual) {
        for (CamionEstado c : contexto.getCamiones()) {
            // 0) Está averiado => no procesar en absoluto
            if (c.getStatus() == CamionEstado.TruckStatus.BREAKDOWN || c.getStatus() == CamionEstado.TruckStatus.MAINTENANCE) {
                // Camión averiado, no se procesa en ningún caso
                continue;
            }
            
            // 1) Está descargando/recargando => no avanza
            if (c.getStatus() == CamionEstado.TruckStatus.UNAVAILABLE){
                continue;
            }

            // 2) Mover camiones que están en ruta (entregando o retornando)
            if (c.getStatus() == CamionEstado.TruckStatus.DELIVERING) {
                if (c.tienePasosPendientes()) {
                    c.avanzarUnPaso();
                    contexto.setTotalDistanciaRecorrida(contexto.getTotalDistanciaRecorrida() + 1);
                }
            }

            // Lógica de retorno (sin avance, que ya se hizo arriba)
            if (c.getStatus() == CamionEstado.TruckStatus.RETURNING) {
                if (c.tienePasosPendientes()) {
                    c.avanzarUnPaso();
                    contexto.setTotalDistanciaRecorrida(contexto.getTotalDistanciaRecorrida() + 1);
                } else {
                    // llegó al depósito: programa recarga 15'
                    c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                    c.setCapacidadDisponible(c.getPlantilla().getCapacidadCarga());
                    c.setCombustibleDisponible(c.getPlantilla().getCapacidadCombustible()); 
                    c.setTiempoLibre(tiempoActual.plusMinutes(TIEMPO_SERVICIO));
                    c.getPedidosCargados().clear();
                    for(TanqueDinamico t : contexto.getTanques()) {
                        if (t.getPosX() == c.getX() && t.getPosY() == c.getY()) {
                            if(t.equals(contexto.getTanques().get(0))) {
                                c.setTiempoLibre(tiempoActual);
                            }
                            c.setTanqueOrigen(t);
                            break;
                        }
                    }
                }
                continue;
            }

            // 3) Ruta de entrega/desvío
            if (c.getStatus() == CamionEstado.TruckStatus.DELIVERING
                    && c.tienePasosPendientes()) {
                //c.avanzarUnPaso();
                continue;
            }

            // 4) AVAILABLE con ruta vacía → simplemente espera asignación
        }
    }

    

}