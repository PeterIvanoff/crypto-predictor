package com.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class PredictionController {
    private final NeuralNetwork neuralNetwork;
    private final DatabaseManager databaseManager;
    private double latestPrediction = 0.0;

    @Autowired
    public PredictionController(NeuralNetwork neuralNetwork, DatabaseManager databaseManager) {
        this.neuralNetwork = neuralNetwork;
        this.databaseManager = databaseManager;
    }

    @GetMapping("/prediction")
    public Map<String, Object> getPrediction() {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", System.currentTimeMillis());
        response.put("currencyPair", Constants.CURRENCY_PAIR);
        response.put("predictedPrice", latestPrediction);
        return response;
    }

    public void updatePrediction(double prediction) {
        this.latestPrediction = prediction;
    }
}