package pe.pucp.plg.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import pe.pucp.plg.dto.EventDTO;

@Service
public class EventPublisherService {

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public EventPublisherService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Publica un evento al topic global de operaciones (/topic/operations)
     */
    public void publicarEventoOperacion(EventDTO evento) {
        messagingTemplate.convertAndSend("/topic/operations", evento);
    }

    /**
     * Publica un evento al topic de una simulación específica (/topic/simulation/{id})
     */
    public void publicarEventoSimulacion(String simulationId, EventDTO evento) {
        String destino = "/topic/simulation/" + simulationId;
        messagingTemplate.convertAndSend(destino, evento);
    }
}
