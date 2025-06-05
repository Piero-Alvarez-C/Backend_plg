package pe.pucp.plg.util;

import pe.pucp.plg.dto.CamionDTO;
import pe.pucp.plg.dto.PedidoDTO;
import pe.pucp.plg.dto.TanqueDTO;
import pe.pucp.plg.dto.BloqueoDTO;
import pe.pucp.plg.model.Camion;
import pe.pucp.plg.model.Pedido;
import pe.pucp.plg.model.Tanque;
import pe.pucp.plg.model.Bloqueo;
public class MapperUtil {

    public static CamionDTO toDTO(Camion camion) {
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
    public static TanqueDTO toDTO(Tanque tanque) {
        TanqueDTO dto = new TanqueDTO();
        dto.setPosX(tanque.getPosX());
        dto.setPosY(tanque.getPosY());
        dto.setCapacidadTotal(tanque.getCapacidadTotal());
        dto.setCapacidadDisponible(tanque.getDisponible());
        return dto;
    }
    public static PedidoDTO toDTO(Pedido pedido) {
        PedidoDTO dto = new PedidoDTO();
        dto.setId(pedido.getId());
        dto.setX(pedido.getX());
        dto.setY(pedido.getY());
        dto.setTiempoCreacion(pedido.getTiempoCreacion());
        dto.setTiempoLimite(pedido.getTiempoLimite());
        dto.setVolumen(pedido.getVolumen());
        dto.setAtendido(pedido.isAtendido());
        dto.setDescartado(pedido.isDescartado());
        dto.setIdCliente("C" + String.format("%03d", pedido.getId())); // ← crea ID de cliente ficticio
        return dto;
    }
    public static BloqueoDTO toDTO(Bloqueo bloqueo) {
        BloqueoDTO dto = new BloqueoDTO();
        dto.setStartMin(bloqueo.getStartMin());
        dto.setEndMin(bloqueo.getEndMin());
        dto.setNodes(bloqueo.getNodes()); // List<Point>
        return dto;
    }

    // Agregar más métodos:
    // public static PedidoDTO toDTO(Pedido pedido) { ... }
    // public static EventoDTO toDTO(Evento evento) { ... }
}
