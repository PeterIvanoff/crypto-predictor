package com.crypto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@SpringBootApplication
@EnableScheduling
@EnableWebSocket
public class Main implements WebSocketConfigurer {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(predictionWebSocketHandler(), "/predictions").setAllowedOrigins("*");
    }

    @Bean
    public PredictionWebSocketHandler predictionWebSocketHandler() {
        return new PredictionWebSocketHandler();
    }

    @Bean
    public DatabaseManager databaseManager() {
        return new DatabaseManager();
    }

    @Bean
    public BybitClient bybitClient(DatabaseManager databaseManager, Indicators indicators,
                                   ImbalanceZones imbalanceZones, NeuralNetwork neuralNetwork,
                                   PredictionWebSocketHandler webSocketHandler,
                                   PredictionController predictionController) {
        return new BybitClient(databaseManager, indicators, imbalanceZones, neuralNetwork,
                webSocketHandler, predictionController);
    }

    @Bean
    public Indicators indicators(DatabaseManager databaseManager) {
        return new Indicators(databaseManager);
    }

    @Bean
    public ImbalanceZones imbalanceZones(DatabaseManager databaseManager) {
        return new ImbalanceZones(databaseManager);
    }

    @Bean
    public NeuralNetwork neuralNetwork(DatabaseManager databaseManager, Indicators indicators,
                                       ImbalanceZones imbalanceZones) {
        return new NeuralNetwork(databaseManager, indicators, imbalanceZones);
    }

    @Bean
    public PredictionController predictionController(NeuralNetwork neuralNetwork,
                                                     DatabaseManager databaseManager) {
        return new PredictionController(neuralNetwork, databaseManager);
    }
}