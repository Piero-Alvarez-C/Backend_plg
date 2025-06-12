package pe.pucp.plg.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class BloqueoDTO {
    /** Un identificador único para este bloqueo */
    private String id;
    private int tiempoInicio;
    private int tiempoFin;
    private List<PointDTO> nodes;
    /** Texto descriptivo */
    @JsonProperty("description")
    private String description;

    public BloqueoDTO() {}
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public int getTiempoInicio() { return tiempoInicio; }
    public int getTiempoFin() { return tiempoFin; }
    public List<PointDTO> getNodes() { return nodes; }
    public String getDescription() { return description; }
    public void setTiempoInicio(int tiempoInicio) {
        this.tiempoInicio = tiempoInicio;
    }
    public void setTiempoFin(int tiempoFin) {
        this.tiempoFin = tiempoFin;
    }
    public void setNodes(List<PointDTO> nodes) { this.nodes = nodes; }
    public void setDescription(String description) { this.description = description; }
}
