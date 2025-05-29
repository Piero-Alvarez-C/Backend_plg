package pe.pucp.plg.service;

import pe.pucp.plg.model.*;

import java.util.List;

public interface PlanificadorService {
    void setPedidos(List<Pedido> pedidos);
    List<Pedido> getPedidos();
    void setMantenimientos(List<Mantenimiento> mantenimientos);
    void setBloqueos(List<Bloqueo> bloqueos);
    void setAverias(List<Averia> averias);

    void ejecutarPlanificacion(); // punto de entrada al algoritmo
}
