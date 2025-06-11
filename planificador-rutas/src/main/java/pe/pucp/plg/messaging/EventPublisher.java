package pe.pucp.plg.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import pe.pucp.plg.dto.EventDTO;

@Component
public class EventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public EventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // Evento general para modo operativo
    public void publishToOperations(EventDTO event) {
        messagingTemplate.convertAndSend("/topic/operations", event);
    }

    // Evento específico para una simulación
    public void publishToSimulation(String simulationId, EventDTO event) {
        String topic = "/topic/simulation/" + simulationId;
        messagingTemplate.convertAndSend(topic, event);
    }
}
