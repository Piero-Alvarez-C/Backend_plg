package pe.pucp.plg.util;

import pe.pucp.plg.dto.*;
import pe.pucp.plg.model.common.Averia;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MapperUtil {

    public static CamionDTO toCamionDTO(CamionEstado camion) {
        CamionDTO dto = new CamionDTO();
        dto.setId(camion.getPlantilla().getId()); 
        dto.setX(camion.getX()); // Corrected
        dto.setY(camion.getY()); // Corrected
        dto.setDisponible(camion.getCapacidadDisponible()); 
        dto.setCombustibleDisponible(camion.getCombustibleActual()); // Corrected
        dto.setStatus(camion.getStatus().name());
        // dto.setConsumoAcumulado(camion.getConsumoAcumulado()); // Getter does not exist, commented out for now
        if (camion.getRutaActual() != null) {
            List<PointDTO> ruta = camion.getRutaActual().stream()
                    .map(MapperUtil::toPointDTO)
                    .collect(Collectors.toList());
            dto.setRuta(ruta);
        }
        
        List<String> pedidosIds = new ArrayList<>();
        
        // Agregar IDs de pedidos cargados
        if (camion.getPedidosCargados() != null) {
            for (Pedido pedido : camion.getPedidosCargados()) {
                pedidosIds.add(String.valueOf(pedido.getId()));
            }
        }
        
        // Agregar ID de pedido de desvío si existe
        if (camion.getPedidoDesvio() != null) {
            if (camion.getPedidosCargados().isEmpty() || 
                camion.getPedidoDesvio().getId() != camion.getPedidosCargados().get(0).getId()) {
                
                pedidosIds.add(String.valueOf(camion.getPedidoDesvio().getId()));
            }
        }
        
        dto.setPedidos(pedidosIds);
        return dto;
    }

    public static TanqueDTO toTanqueDTO(TanqueDinamico tanque) {
        TanqueDTO dto = new TanqueDTO();
        dto.setId(tanque.getId());
        dto.setPosX(tanque.getPosX());
        dto.setPosY(tanque.getPosY());
        dto.setCapacidadTotal(tanque.getCapacidadTotal()); // Corrected: Direct getter from TanqueDinamico
        dto.setCapacidadDisponible(tanque.getDisponible()); // Corrected: Renamed from getCapacidadActual
        dto.setPedidos(tanque.getPedidos()); // Added: Get pedidos from TanqueDinamico
        return dto;
    }

    public static PedidoDTO toPedidoDTO(Pedido pedido) {
        PedidoDTO dto = new PedidoDTO();
        dto.setId(pedido.getId());
        String idCompleto = pedido.getId();
        String idBase = idCompleto.contains("-") ? idCompleto.split("-")[0] : idCompleto;
        int idNumerico = Integer.parseInt(idBase);
        dto.setIdCliente("C" + String.format("%03d", idNumerico));
        dto.setX(pedido.getX());
        dto.setY(pedido.getY());
        dto.setTiempoCreacion(pedido.getTiempoCreacion());
        dto.setTiempoLimite(pedido.getTiempoLimite());
        dto.setVolumen(pedido.getVolumen());
        dto.setAtendido(pedido.isAtendido());
        dto.setDescartado(pedido.isDescartado());
        dto.setProgramado(pedido.isProgramado());
        dto.setEnEntrega(pedido.isEnEntrega());
        return dto;
    }

    public static BloqueoDTO toBloqueoDTO(Bloqueo bloqueo) {
        BloqueoDTO dto = new BloqueoDTO();
        dto.setId("B-" + bloqueo.getStartTime() + "-" + bloqueo.getEndTime());
        dto.setTiempoInicio(bloqueo.getStartTime());
        dto.setTiempoFin(bloqueo.getEndTime());
        dto.setDescription(bloqueo.getDescription());
        List<PointDTO> nodesDto = bloqueo.getNodes().stream()
                .map(MapperUtil::toPointDTO)
                .collect(Collectors.toList());
        dto.setNodes(nodesDto);
        return dto;
    }

    public static PointDTO toPointDTO(Point p) {
        PointDTO dto = new PointDTO(p.x, p.y);
        return dto;
    }

    // Note: CamionEstadoDTO might need tiempoActual for getTiempoLibre
    // Passing 0 for now, or consider if this DTO is used where tiempoActual isn't known
    public static CamionEstadoDTO toCamionEstadoDTO(CamionEstado est, int tiempoActual) {
        CamionEstadoDTO dto = new CamionEstadoDTO();
        dto.setId(est.getPlantilla().getId()); 
        dto.setPosX(est.getX()); // Corrected
        dto.setPosY(est.getY()); // Corrected
        dto.setCapacidadDisponible(est.getCapacidadDisponible()); // Corrected
        dto.setTiempoLibre(est.getTiempoLibre()); // Corrected, requires tiempoActual
        dto.setTara(est.getPlantilla().getTara()); 
        dto.setCombustibleDisponible(est.getCombustibleActual()); // Corrected
        return dto;
    }

    public static RutaDTO toRutaDTO(Ruta ruta) {
        RutaDTO dto = new RutaDTO();
        // dto.setEstadoCamion(toCamionEstadoDTO(ruta.getEstadoCamion())); // estadoCamion removed from Ruta model
        dto.setCamionId(ruta.getCamionId()); // Added camionId to DTO
        if (ruta.getPedidoIds() != null) {
             dto.setPedidos(new ArrayList<>(ruta.getPedidoIds()));
        } else {
            dto.setPedidos(new ArrayList<>());
        }
        dto.setDistancia(ruta.distancia);
        dto.setConsumo(ruta.consumo); 
        return dto;
    }

    /** Opcional: mapear todo el snapshot de la simulación */
    public static SimulacionSnapshotDTO toSnapshotDTO(ExecutionContext estado) {
        SimulacionSnapshotDTO s = new SimulacionSnapshotDTO();
        s.setTiempoActual(estado.getCurrentTime());
        s.setCamiones(estado.getCamiones().stream()
                .map(camion -> toCamionDTO(camion)).toList()); 
        Map<String, List<Pedido>> pedidosAgrupados = estado.getPedidos().stream()
            .collect(Collectors.groupingBy(p -> {
                String id = p.getId();
                // Esto extrae el ID base, ej: "123-0" -> "123"
                return id.contains("-") ? id.split("-")[0] : id;
            }));

        // 2. Convierte cada grupo en un único PedidoDTO consolidado
        List<PedidoDTO> pedidosConsolidados = pedidosAgrupados.values().stream()
            .map(subPedidos -> {
                // Usa el primer sub-pedido como plantilla para los datos comunes
                Pedido plantilla = subPedidos.get(0);
                
                // Suma el volumen de todos los sub-pedidos del grupo
                double volumenTotal = subPedidos.stream()
                                                .mapToDouble(Pedido::getVolumen)
                                                .sum();

                // Crea el DTO consolidado
                PedidoDTO dto = MapperUtil.toPedidoDTO(plantilla); // Copia los datos base
                dto.setVolumen(volumenTotal); // Sobrescribe con el volumen total
                dto.setId(plantilla.getId().split("-")[0]); // Usa el ID base limpio

                // (Opcional) Determina un estado representativo para el grupo
                boolean estaProgramado = subPedidos.stream().anyMatch(Pedido::isProgramado);
                boolean estaEnEntrega = subPedidos.stream().anyMatch(Pedido::isEnEntrega);
                dto.setProgramado(estaProgramado);
                dto.setEnEntrega(estaEnEntrega);

                return dto;
            })
            .collect(Collectors.toList());

        s.setPedidos(pedidosConsolidados);
        s.setBloqueos(estado.getBloqueosActivos().stream()
                .map(MapperUtil::toBloqueoDTO).toList());
        s.setTanques(estado.getTanques().stream()
                .map(MapperUtil::toTanqueDTO).toList());
                
        s.setAverias(
                estado.getAveriasPorTurno().entrySet().stream()
                        .flatMap(turnoEntry -> turnoEntry.getValue().entrySet().stream()
                                .map(avEntry -> {
                                    AveriaDTO dto = new AveriaDTO();
                                    dto.setTurno(turnoEntry.getKey());
                                    dto.setCodigoVehiculo(avEntry.getKey());
                                    dto.setTipoIncidente(avEntry.getValue().getTipoIncidente());
                                    return dto;
                                })
                        ).toList());


        // For RutaDTO, we now map camionId. If full CamionEstadoDTO is needed here,
        // it would require looking up CamionEstado from ExecutionContext based on camionId.
        // For simplicity, RutaDTO in snapshot will contain camionId.
        //s.setRutas(estado.getRutas().stream()
        //        .map(MapperUtil::toRutaDTO).toList());
        return s;
    }
    public static AveriaDTO toAveriaDTO(Averia averia) {
        AveriaDTO dto = new AveriaDTO();
        dto.setTipoIncidente(averia.getTipoIncidente());
        dto.setTurno(averia.getTurno());
        dto.setCodigoVehiculo(averia.getCodigoVehiculo());
        return dto;
    }
        
}