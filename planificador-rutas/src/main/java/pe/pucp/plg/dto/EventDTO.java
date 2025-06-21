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

    public EventDTO() {}

    public EventDTO(EventType type, Object payload, long timestamp) {
        this.type = type;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    public static EventDTO of(EventType type, Object payload) {
        return new EventDTO(type, payload, System.currentTimeMillis());
    }

    public EventType getType() { return type; }
    public void setType(EventType type) { this.type = type; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
