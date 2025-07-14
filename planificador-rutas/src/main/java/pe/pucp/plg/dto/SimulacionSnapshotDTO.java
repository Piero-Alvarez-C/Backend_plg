package pe.pucp.plg.dto;

import java.time.LocalDateTime;
import java.util.List;

public class SimulacionSnapshotDTO {
    private LocalDateTime tiempoActual;
    private List<CamionDTO> camiones;
    private List<PedidoDTO> pedidos;
    private List<BloqueoDTO> bloqueos;
    private List<TanqueDTO> tanques;
    private List<AveriaDTO> averias;
    private List<RutaDTO> rutas;

    // Getters y Setters
    public LocalDateTime getTiempoActual() {
        return tiempoActual;
    }

    public void setTiempoActual(LocalDateTime tiempoActual) {
        this.tiempoActual = tiempoActual;
    }

    public List<CamionDTO> getCamiones() {
        return camiones;
    }

    public void setCamiones(List<CamionDTO> camiones) {
        this.camiones = camiones;
    }

    public List<PedidoDTO> getPedidos() {
        return pedidos;
    }

    public void setPedidos(List<PedidoDTO> pedidos) {
        this.pedidos = pedidos;
    }

    public List<BloqueoDTO> getBloqueos() {
        return bloqueos;
    }

    public void setBloqueos(List<BloqueoDTO> bloqueos) {
        this.bloqueos = bloqueos;
    }

    public List<TanqueDTO> getTanques() {
        return tanques;
    }

    public void setTanques(List<TanqueDTO> tanques) {
        this.tanques = tanques;
    }
    public List<AveriaDTO> getAverias() {
        return averias;
    }

    public void setAverias(List<AveriaDTO> averias) {
        this.averias = averias;
    }

    public List<RutaDTO> getRutas() {
        return rutas;
    }
    public void setRutas(List<RutaDTO> rutas) {
        this.rutas = rutas;
    }
}