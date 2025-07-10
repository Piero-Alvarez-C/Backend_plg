package pe.pucp.plg.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Prefijo para los mensajes salientes (cliente se suscribe a /topic/...)
        config.enableSimpleBroker("/topic");
        // Prefijo para los mensajes entrantes desde cliente al servidor (no lo usaremos de momento)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Punto de conexión del frontend al WebSocket
        registry.addEndpoint("/ws-connect")
                .setAllowedOriginPatterns("*").withSockJS();
    }
}
