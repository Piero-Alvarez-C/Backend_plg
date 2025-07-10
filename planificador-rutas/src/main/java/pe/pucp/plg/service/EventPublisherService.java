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
        String topic = "/topic/operations";
        messagingTemplate.convertAndSend(topic, evento);
        System.out.println("Publicado evento en " + topic + ": " + evento);
    }

    public void publicarEventoSimulacion(String simulationId, EventDTO evento) {
        if ("operational".equals(simulationId)) {
            String topic = "/topic/operations";
            messagingTemplate.convertAndSend(topic, evento);
            System.out.println("Publicado evento en " + topic + ": " + evento);
        } else {
            String destino = "/topic/simulation/" + simulationId;
            messagingTemplate.convertAndSend(destino, evento);
            System.out.println("Publicado evento en " + destino + ": " + evento);
        }
    }
}
