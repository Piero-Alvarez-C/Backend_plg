package pe.pucp.plg.service.Impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.pucp.plg.model.common.Averia;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Mantenimiento;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.service.CamionService;
import pe.pucp.plg.service.PlanificadorService;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlanificadorServiceImpl implements PlanificadorService {

    private List<Pedido> pedidos = new ArrayList<>();
    private List<Mantenimiento> mantenimientos = new ArrayList<>();
    private List<Bloqueo> bloqueos = new ArrayList<>();
    private List<Averia> averias = new ArrayList<>();
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
        //List<Camion> camiones = camionService.getFlota();

        System.out.println("> Ejecutando algoritmo de planificación...");
        System.out.println("Pedidos: " + pedidos.size());
        System.out.println("Mantenimientos: " + mantenimientos.size());
        System.out.println("Bloqueos: " + bloqueos.size());
        System.out.println("Averías: " + averias.size());
        //System.out.println("Camiones: " + camiones.size());
        //pedidos.add(new Pedido(LocalDateTime.of(2025, 5, 29, 10, 0),
        //        45, 43, "c-123", 9, 36));
        //pedidos.add(new Pedido(LocalDateTime.of(2025, 5, 29, 12, 30),
        //        30, 20, "c-456", 15, 24));
        // pedidos.add(new Pedido(LocalDateTime.of(2025, 5, 29, 13, 15),
        //        50, 10, "c-789", 5, 12));


        // Aquí llamas a tu algoritmo metaheurístico (por ejemplo, ACO)
        // ACO.resolver(pedidos, bloqueos, mantenimientos, averias);
    }
}
