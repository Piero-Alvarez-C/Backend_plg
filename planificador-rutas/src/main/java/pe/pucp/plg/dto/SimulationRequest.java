package pe.pucp.plg.dto;

public class SimulationRequest {
    private String nombreSimulacion;
    private String fileIdPedidos;
    private String fileIdBloqueos;
    private String fileIdAverias;

    public String getNombreSimulacion() {
        return nombreSimulacion;
    }

    public void setNombreSimulacion(String nombreSimulacion) {
        this.nombreSimulacion = nombreSimulacion;
    }

    public String getFileIdPedidos() {
        return fileIdPedidos;
    }

    public void setFileIdPedidos(String fileIdPedidos) {
        this.fileIdPedidos = fileIdPedidos;
    }

    public String getFileIdBloqueos() {
        return fileIdBloqueos;
    }

    public void setFileIdBloqueos(String fileIdBloqueos) {
        this.fileIdBloqueos = fileIdBloqueos;
    }

    public String getFileIdAverias() {
        return fileIdAverias;
    }

    public void setFileIdAverias(String fileIdAverias) {
        this.fileIdAverias = fileIdAverias;
    }
}
