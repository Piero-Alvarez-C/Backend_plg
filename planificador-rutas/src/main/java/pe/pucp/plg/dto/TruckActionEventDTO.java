package pe.pucp.plg.dto;

public class TruckActionEventDTO {
    private String truckId;       // antes: camionId
    private String actionType;    // antes: accion

    public TruckActionEventDTO(String truckId, String actionType) {
        this.truckId = truckId;
        this.actionType = actionType;
    }

    // Getters y setters
    public String getTruckId() {
        return truckId;
    }

    public void setTruckId(String truckId) {
        this.truckId = truckId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }
}
