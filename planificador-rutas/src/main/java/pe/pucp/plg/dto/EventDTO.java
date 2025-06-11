package pe.pucp.plg.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.pucp.plg.dto.enums.EventType;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventDTO {
    private EventType type;
    private Object payload;
    private long timestamp;

    public static EventDTO of(EventType type, Object payload) {
        return new EventDTO();
    }

    // Getters y setters
    public EventType getType() { return type; }
    public void setType(EventType type) { this.type = type; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
