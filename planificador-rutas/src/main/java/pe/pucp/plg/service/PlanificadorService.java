package pe.pucp.plg.service;

import pe.pucp.plg.model.common.Averia;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Mantenimiento;
import pe.pucp.plg.model.common.Pedido;

import java.util.List;

public interface PlanificadorService {
    void setPedidos(List<Pedido> pedidos);
    List<Pedido> getPedidos();
    void setMantenimientos(List<Mantenimiento> mantenimientos);
    void setBloqueos(List<Bloqueo> bloqueos);
    void setAverias(List<Averia> averias);

    void ejecutarPlanificacion(); // punto de entrada al algoritmo
}
