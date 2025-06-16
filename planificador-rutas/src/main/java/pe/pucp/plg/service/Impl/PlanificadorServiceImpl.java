package pe.pucp.plg.service.Impl;

import org.springframework.stereotype.Service;
import pe.pucp.plg.model.common.Averia;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Mantenimiento;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.service.PlanificadorService;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlanificadorServiceImpl implements PlanificadorService {

    private List<Pedido> pedidos = new ArrayList<>();
    private List<Mantenimiento> mantenimientos = new ArrayList<>();
    private List<Bloqueo> bloqueos = new ArrayList<>();
    private List<Averia> averias = new ArrayList<>();

    @Override
    public void setPedidos(List<Pedido> pedidos) {
        this.pedidos = pedidos;
    }

    public List<Pedido> getPedidos() {
        return pedidos;
    }

    @Override
    public void setMantenimientos(List<Mantenimiento> mantenimientos) {
        this.mantenimientos = mantenimientos;
    }

    @Override
    public void setBloqueos(List<Bloqueo> bloqueos) {
        this.bloqueos = bloqueos;
    }

    @Override
    public void setAverias(List<Averia> averias) {
        this.averias = averias;
    }

    @Override
    public void ejecutarPlanificacion() {
        System.out.println("> Ejecutando algoritmo de planificación...");
        System.out.println("Pedidos: " + pedidos.size());
        System.out.println("Mantenimientos: " + mantenimientos.size());
        System.out.println("Bloqueos: " + bloqueos.size());
        System.out.println("Averías: " + averias.size());
    }
}
