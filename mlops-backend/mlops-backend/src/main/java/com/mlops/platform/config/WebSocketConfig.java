package com.mlops.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import org.springframework.messaging.simp.config.MessageBrokerRegistry;

import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig
        implements WebSocketMessageBrokerConfigurer {

    /**
     * Origines autorisées pour WebSocket.
     *
     * Dans AKS, cette valeur est fournie par :
     * APP_CORS_ALLOWED_ORIGINS
     */
    @Value("${app.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(
            MessageBrokerRegistry registry
    ) {

        /*
         * Destinations auxquelles Angular s’abonne :
         *
         * /topic/users/{userId}/notifications
         * /topic/admin/notifications
         */
        registry.enableSimpleBroker(
                "/topic",
                "/queue"
        );

        /*
         * Préfixe utilisé par le frontend pour envoyer
         * des messages vers le backend.
         */
        registry.setApplicationDestinationPrefixes(
                "/app"
        );

        /*
         * Préfixe facultatif pour les messages privés.
         */
        registry.setUserDestinationPrefix(
                "/user"
        );
    }

    @Override
    public void registerStompEndpoints(
            StompEndpointRegistry registry
    ) {

        String[] origins =
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isBlank())
                        .distinct()
                        .toArray(String[]::new);

        registry
                .addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();
    }
}