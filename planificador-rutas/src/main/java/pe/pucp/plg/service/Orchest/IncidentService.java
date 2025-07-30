package pe.pucp.plg.service.Orchest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import pe.pucp.plg.model.common.Averia;
import pe.pucp.plg.model.common.EntregaEvent;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;

@Service
public class IncidentService {
    
    private final FleetService fleetService;

    @Autowired
    public IncidentService(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    // Proccess breakdowns
    public boolean procesarAverias(ExecutionContext contexto, LocalDateTime tiempoActual) {
        boolean replanificar = false;
        
        // Determinar el turno actual
        String turnoActual = turnoDeDateTime(tiempoActual);
        
        // Si cambió el turno, limpiar estados de averías anteriores
        if (!turnoActual.equals(contexto.getTurnoAnterior())) {
            contexto.setTurnoAnterior(turnoActual);
            //contexto.getAveriasAplicadas().clear();
            
        }
        // Aplicar averías programadas para este turno
        Map<String, Averia> averiasTurno = contexto.getAveriasPorTurno().getOrDefault(turnoActual, Collections.emptyMap());
        for (Map.Entry<String, Averia> entry : averiasTurno.entrySet()) {
            String key = turnoActual + "_" + entry.getKey();
            if (contexto.getAveriasAplicadas().contains(key)) continue;   
            CamionEstado c = fleetService.findCamion(entry.getKey(), contexto);
            Averia datoaveria = entry.getValue();
            // Para averías cargadas desde archivo
            if (datoaveria.isFromFile()) {  
                // Solo aplicar si el camión está entregando y tiene una ruta asignada
                if (c != null && c.getStatus() == CamionEstado.TruckStatus.DELIVERING && 
                    c.getRutaActual() != null && !c.getRutaActual().isEmpty()) {         
                    Integer puntoAveria = contexto.getPuntosAveria().get(entry.getKey());
                    // Solo aplicar si hay un punto de avería calculado y el camión está en ese punto
                    if (puntoAveria != null && c.getPasoActual() == puntoAveria) {
                        
                        if (aplicarAveria(c, datoaveria, tiempoActual, turnoActual, contexto, key)) {
                            replanificar = true;
                            // Eliminar el punto de avería usado para permitir futuras averías.
                            contexto.getPuntosAveria().remove(entry.getKey());
                        }
                    }
                }
            } else {

                if (c != null && (c.getTiempoLibreAveria() == null || !c.getTiempoLibreAveria().isAfter(tiempoActual))){

                    if (aplicarAveria(c, datoaveria, tiempoActual, turnoActual, contexto, key)) {
                        replanificar = true;
                    }
                }
            }

        }
        
        // Revisar camiones que ya pueden volver a servicio
        Iterator<String> it = contexto.getCamionesInhabilitados().iterator();
        while (it.hasNext()) {
            String camionId = it.next();
            CamionEstado c = fleetService.findCamion(camionId, contexto);
            String tipoAveria = c.getTipoAveriaActual();
            // Verificamos si el camión ya ha cumplido su tiempo de inmovilización
            // Calcular y mostrar el tiempo transcurrido desde que se declaró la avería
            long minutosTranscurridos = java.time.Duration.between(c.getTiempoInicioAveria(), tiempoActual).toMinutes();
            
            // Solo teleportar si se ha cumplido el tiempo de inmovilización y no está en taller
            if (tipoAveria != null && !c.isEnTaller() && 
                ((tipoAveria.equals("T2") && minutosTranscurridos > 120) || 
                 (tipoAveria.equals("T3") && minutosTranscurridos > 240))) {
                
                // Para averías T2 y T3, teleportar a posición de origen
                c.setX(c.getPlantilla().getInitialX());
                c.setY(c.getPlantilla().getInitialY());
                // Marcar como en taller para evitar teleportaciones repetidas
                c.setEnTaller(true);
                
            }

            // Si el camión está disponible o su tiempo libre ha expirado
            if (c != null && (c.getTiempoLibreAveria() == null || !c.getTiempoLibreAveria().isAfter(tiempoActual))) {
                it.remove();
                // Restaurar estado del camión tras reparación
                // Reiniciar estado de taller para futuras averías
                c.setEnTaller(false);
                c.setStatus(CamionEstado.TruckStatus.AVAILABLE);
                c.setTanqueOrigen(contexto.getTanques().get(0)); // Asignar la planta
                c.setTiempoLibreAveria(null);
                c.setRuta(java.util.Collections.emptyList());
                c.setPasoActual(0);
                c.setCapacidadDisponible(c.getPlantilla().getCapacidadCarga());
                // Limpiar el tipo de avería ya que el camión está reparado
                
                c.setTipoAveriaActual(null);
                replanificar = true;
            }
        }
        
        return replanificar;
    }

    
    public int calcularTiempoAveria(String turnoActual, String tipoIncidente, LocalDateTime tiempoActual) {
        int inactividad = 0;
        
        // El cálculo para el turno actual sigue igual
        int minutosEnTurno = (tiempoActual.getHour() * 60 + tiempoActual.getMinute()) % 480;
        int minutosHastaFinTurno = 480 - minutosEnTurno;

        switch (tipoIncidente) {
            case "T1":
                inactividad = 120;
                break;

            case "T2":
                inactividad = 120; // Inmovilización inicial
                switch (turnoActual) {
                    // Esta lógica se mantiene
                    case "T1": inactividad += minutosHastaFinTurno + 480; break;
                    case "T2": inactividad += minutosHastaFinTurno + 480; break;
                    case "T3": inactividad += minutosHastaFinTurno + 480; break;
                }
                break;

            case "T3":
                inactividad = 240; // Inmovilización inicial
                
                // --- CÁLCULO CORREGIDO Y SIMPLIFICADO ---
                LocalDateTime diaA3 = tiempoActual.toLocalDate().plusDays(2).atStartOfDay(); // 00:00 del día A+2 (inicio del día 3)
                long minutosHastaDiaA3 = java.time.Duration.between(tiempoActual, diaA3).toMinutes();
                
                inactividad += (int) minutosHastaDiaA3;
                break;

            default:
                System.out.println("Tipo de incidente desconocido: " + tipoIncidente);
                break;
        }

        return inactividad;
    }

    public void calcularPuntosAveria(CamionEstado camion, ExecutionContext contexto) {
        String idCamion = camion.getPlantilla().getId();
        int totalPasos = camion.getRutaActual().size();
        
        // Si la ruta es muy corta, no calculamos puntos de avería
        if (totalPasos < 5) return;
    
        // Recorrer el mapa de averías por turno (T1, T2, T3)
        for (Map.Entry<String, Map<String, Averia>> entryTurno : contexto.getAveriasPorTurno().entrySet()) {
            Map<String, Averia> averiasPorCamion = entryTurno.getValue();
            
            // Verificar si hay una avería para este camión en este turno
            if (averiasPorCamion.containsKey(idCamion)) {
                Averia averia = averiasPorCamion.get(idCamion);
                
                // Solo procesamos averías cargadas desde archivo
                if (averia.isFromFile()) {
                    if (contexto.getPuntosAveria().containsKey(idCamion)) continue;
                    // Calcular punto aleatorio para esta avería
                    Random random = new Random();
                    int pasoMinimo = Math.max(1, (int) (totalPasos * 0.05)); // 5% de la ruta
                    int pasoMaximo = Math.max(pasoMinimo + 1, (int) (totalPasos * 0.35)); // 35% de la ruta
                    int pasoAveria = pasoMinimo + random.nextInt(pasoMaximo - pasoMinimo + 1);
                    
                    // Guardar el punto de avería en el contexto
                    contexto.getPuntosAveria().put(idCamion, pasoAveria);
                }
            }
        }
    }
    
    private boolean aplicarAveria(CamionEstado camion, Averia averia, LocalDateTime tiempoActual, 
                            String turnoActual, ExecutionContext contexto, String key) {
        // Determinar penalización según tipo de avería
        //int minutosActuales = tiempoActual.getHour() * 60 + tiempoActual.getMinute();
        int penal = calcularTiempoAveria(turnoActual, averia.getTipoIncidente(), tiempoActual);
        camion.setTiempoLibreAveria(tiempoActual.plusMinutes(penal));
        camion.setTiempoInicioAveria(tiempoActual); // Guardar cuándo inicia la avería
        camion.setStatus(CamionEstado.TruckStatus.BREAKDOWN);
        // Guardar el tipo de avería en el camión para usarlo después
        camion.setTipoAveriaActual(averia.getTipoIncidente());
        // Reiniciar estado de taller para permitir teleportación si es necesario
        camion.setEnTaller(false);

        // --- Acciones inmediatas por avería ---
        // Remover eventos de entrega pendientes para este camión
       // removerEventosEntregaDeCamion(camion.getPlantilla().getId(), contexto);
        // Liberar pedidos pendientes y limpiar ruta
        for (Pedido pPend : new ArrayList<>(camion.getPedidosCargados())) {
            pPend.setProgramado(false); // volver a la cola de planificación
            pPend.setHoraEntregaProgramada(null);
            pPend.setAtendido(false); 
        }
        // Limpieza total de datos de rutas y pedidos para el camión averiado
        camion.getPedidosCargados().clear();  // Limpiar pedidos cargados
        camion.setRuta(Collections.emptyList());  // Limpiar ruta actual
        camion.setPasoActual(0);  // Resetear paso actual
        camion.getHistory().clear();  // Limpiar historial de movimientos

        // Restaurar capacidad total del camión (queda vacío tras trasvase virtual)
        camion.setCapacidadDisponible(camion.getPlantilla().getCapacidadCarga());
        contexto.getCamionesInhabilitados().add(camion.getPlantilla().getId());

        // Remover cualquier evento de entrega pendiente para este camión
        removerEventosEntregaDeCamion(camion.getPlantilla().getId(), contexto);

        // Marcar la avería como aplicada
        contexto.getAveriasAplicadas().add(key);
        
        
        return true; // Siempre replanificar después de una avería
    }
    /**
     * Procesa los eventos de entrega que ocurren en el tiempo actual.
     * @param contexto El contexto de ejecución
     * @param tiempoActual El tiempo actual de la simulación
     */
    /**
     * Remueve todos los eventos de entrega pendientes para un camión específico.
     */
    private void removerEventosEntregaDeCamion(String camionId, ExecutionContext contexto) {
        Iterator<EntregaEvent> itEv = contexto.getEventosEntrega().iterator();
        while (itEv.hasNext()) {
            EntregaEvent ev = itEv.next();
            if (ev.getCamionId().equals(camionId)) {
                if (ev.getPedido() != null) {
                    ev.getPedido().setProgramado(false); // Liberar el pedido
                }
                itEv.remove();
            }
        }
    }

    // ------------------------------------------------------------
    // 14) Conversor turno a partir de minuto (“T1”|“T2”|“T3”)
    // ------------------------------------------------------------
    private String turnoDeDateTime(LocalDateTime dateTime) {
        int hour = dateTime.getHour();
        int minute = dateTime.getMinute();
        int minutesOfDay = hour * 60 + minute;
        
        if (minutesOfDay < 480) return "T1"; // Antes de las 8:00
        else if (minutesOfDay < 960) return "T2"; // Entre 8:00 y 16:00
        else return "T3"; // Después de las 16:00
    }

}
