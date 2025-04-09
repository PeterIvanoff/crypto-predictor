package com.crypto;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final PredictionWebSocketHandler predictionWebSocketHandler;

    public WebSocketConfig(PredictionWebSocketHandler predictionWebSocketHandler) {
        this.predictionWebSocketHandler = predictionWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(predictionWebSocketHandler, "/predictions").setAllowedOrigins("*");
    }
}