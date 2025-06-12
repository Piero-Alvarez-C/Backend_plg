package pe.pucp.plg.util;

import pe.pucp.plg.dto.*;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.model.common.Ruta;
import pe.pucp.plg.model.context.SimulacionEstado;
import pe.pucp.plg.model.state.CamionDinamico;
import pe.pucp.plg.model.state.CamionEstado;
import pe.pucp.plg.model.state.TanqueDinamico;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MapperUtil {

    public static CamionDTO toCamionDTO(CamionDinamico camion) {
        CamionDTO dto = new CamionDTO();
        dto.setId(camion.getId());
        dto.setX(camion.getX());
        dto.setY(camion.getY());
        dto.setDisponible(camion.getDisponible());
        dto.setCombustibleDisponible(camion.getCombustibleDisponible());
        dto.setStatus(camion.getStatus().name());
        dto.setConsumoAcumulado(camion.getConsumoAcumulado());
        return dto;
    }

    public static TanqueDTO toTanqueDTO(TanqueDinamico tanque) {
        TanqueDTO dto = new TanqueDTO();
        dto.setPosX(tanque.getPosX());
        dto.setPosY(tanque.getPosY());
        dto.setCapacidadTotal(tanque.getCapacidadTotal());
        dto.setCapacidadDisponible(tanque.getDisponible());
        return dto;
    }

    public static PedidoDTO toPedidoDTO(Pedido pedido) {
        PedidoDTO dto = new PedidoDTO();
        dto.setId(pedido.getId());
        dto.setIdCliente("C" + String.format("%03d", pedido.getId()));
        dto.setX(pedido.getX());
        dto.setY(pedido.getY());
        dto.setTiempoCreacion(pedido.getTiempoCreacion());
        dto.setTiempoLimite(pedido.getTiempoLimite());
        dto.setVolumen(pedido.getVolumen());
        dto.setAtendido(pedido.isAtendido());
        dto.setDescartado(pedido.isDescartado());
        return dto;
    }

    public static BloqueoDTO toBloqueoDTO(Bloqueo bloqueo) {
        BloqueoDTO dto = new BloqueoDTO();
        dto.setId("B-" + bloqueo.getStartMin() + "-" + bloqueo.getEndMin());
        dto.setTiempoInicio(bloqueo.getStartMin());
        dto.setTiempoFin(bloqueo.getEndMin());
        dto.setDescription(bloqueo.getDescription());
        List<PointDTO> nodesDto = bloqueo.getNodes().stream()
                .map(MapperUtil::toPointDTO)
                .collect(Collectors.toList());
        dto.setNodes(nodesDto);
        return dto;
    }

    public static PointDTO toPointDTO(Point p) {
        PointDTO dto = new PointDTO();
        dto.setX(p.x);
        dto.setY(p.y);
        return dto;
    }

    public static CamionEstadoDTO toCamionEstadoDTO(CamionEstado est) {
        CamionEstadoDTO dto = new CamionEstadoDTO();
        dto.setId(est.id);
        dto.setPosX(est.posX);
        dto.setPosY(est.posY);
        dto.setCapacidadDisponible(est.capacidadDisponible);
        dto.setTiempoLibre(est.tiempoLibre);
        dto.setTara(est.tara);
        dto.setCombustibleDisponible(est.combustibleDisponible);
        return dto;
    }

    public static RutaDTO toRutaDTO(Ruta ruta) {
        RutaDTO dto = new RutaDTO();
        dto.setEstadoCamion(toCamionEstadoDTO(ruta.estadoCamion));
        dto.setPedidos(new ArrayList<>(ruta.pedidos));
        dto.setDistancia(ruta.distancia);
        dto.setConsumo(ruta.consumo);
        return dto;
    }

    /** Opcional: mapear todo el snapshot de la simulación */
    public static SimulacionSnapshotDTO toSnapshotDTO(SimulacionEstado estado) {
        SimulacionSnapshotDTO s = new SimulacionSnapshotDTO();
        s.setTiempoActual(estado.getCurrentTime());
        s.setCamiones(estado.getCamiones().stream()
                .map(MapperUtil::toCamionDTO).toList());
        s.setPedidos(estado.getPedidos().stream()
                .map(MapperUtil::toPedidoDTO).toList());
        s.setBloqueos(estado.getBloqueos().stream()
                .map(MapperUtil::toBloqueoDTO).toList());
        s.setTanques(estado.getTanques().stream()
                .map(MapperUtil::toTanqueDTO).toList());
        s.setRutas(estado.getRutas().stream()
                .map(MapperUtil::toRutaDTO).toList());
        return s;
    }
}