package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.model.*;

import java.util.List;

@Service
public class PlanificadorServiceImpl implements PlanificadorService {

    private List<Pedido> pedidos;
    private List<Mantenimiento> mantenimientos;
    private List<Bloqueo> bloqueos;
    private List<Averia> averias;
    @Autowired
    private CamionService camionService;

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
        List<Camion> camiones = camionService.getFlota();

        System.out.println("> Ejecutando algoritmo de planificación...");
        System.out.println("Pedidos: " + pedidos.size());
        System.out.println("Mantenimientos: " + mantenimientos.size());
        System.out.println("Bloqueos: " + bloqueos.size());
        System.out.println("Averías: " + averias.size());
        System.out.println("Camiones: " + camiones.size());


        // Aquí llamas a tu algoritmo metaheurístico (por ejemplo, ACO)
        // ACO.resolver(pedidos, bloqueos, mantenimientos, averias);
    }
}
