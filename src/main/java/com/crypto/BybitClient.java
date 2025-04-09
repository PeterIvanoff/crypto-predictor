package com.crypto;

import jakarta.websocket.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ClientEndpoint
@Component
public class BybitClient {
    private final DatabaseManager dbManager;
    private final Indicators indicators;
    private final ImbalanceZones imbalanceZones;
    private final NeuralNetwork neuralNetwork;
    private final PredictionWebSocketHandler webSocketHandler;
    private final PredictionController predictionController;
    private Session wsSession;
    private ScheduledExecutorService pingExecutor;

    @Autowired
    public BybitClient(DatabaseManager dbManager, Indicators indicators, ImbalanceZones imbalanceZones,
                       NeuralNetwork neuralNetwork, PredictionWebSocketHandler webSocketHandler,
                       PredictionController predictionController) {
        this.dbManager = dbManager;
        this.indicators = indicators;
        this.imbalanceZones = imbalanceZones;
        this.neuralNetwork = neuralNetwork;
        this.webSocketHandler = webSocketHandler;
        this.predictionController = predictionController;
        loadHistoricalData();
        indicators.calculateAndSaveIndicators();
        imbalanceZones.calculateAndSaveZones();
        connectWebSocket();
        predictAndBroadcast(); // Начальное предсказание
    }

    public void loadHistoricalData() {
        long lastTimestamp = dbManager.getLastCandleTimestamp();
        long now = System.currentTimeMillis();
        if (lastTimestamp == 0) lastTimestamp = now - Constants.TRAINING_PERIOD * 15 * 60 * 1000;

        HttpClient client = HttpClient.newHttpClient();
        int totalCandlesLoaded = 0;
        long currentStart = lastTimestamp;

        while (totalCandlesLoaded < Constants.TRAINING_PERIOD) {
            String url = Constants.BYBIT_API_URL + "/v5/market/kline?category=linear&symbol=" + Constants.CURRENCY_PAIR +
                    "&interval=" + Constants.TIMEFRAME + "&start=" + currentStart + "&end=" + now + "&limit=200";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                JSONObject json = new JSONObject(response.body());
                JSONArray result = json.getJSONObject("result").getJSONArray("list");

                if (result.length() == 0) break; // Нет больше данных

                for (int i = result.length() - 1; i >= 0; i--) {
                    JSONArray candle = result.getJSONArray(i);
                    long timestamp = candle.getLong(0);
                    double open = candle.getDouble(1);
                    double high = candle.getDouble(2);
                    double low = candle.getDouble(3);
                    double close = candle.getDouble(4);
                    double volume = candle.getDouble(5);
                    dbManager.saveCandle(timestamp, open, high, low, close, volume);
                    totalCandlesLoaded++;
                }

                // Сдвигаем start на самый ранний timestamp из полученных данных
                currentStart = result.getJSONArray(result.length() - 1).getLong(0) - 15 * 60 * 1000;
                System.out.println("Loaded " + totalCandlesLoaded + " candles so far...");

                if (totalCandlesLoaded >= Constants.TRAINING_PERIOD) break;

            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                break;
            }
        }
        System.out.println("Total loaded " + totalCandlesLoaded + " candles into database.");
    }

    private void connectWebSocket() {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            wsSession = container.connectToServer(this, new URI(Constants.BYBIT_WS_URL));

            wsSession.getAsyncRemote().sendText("{\"op\": \"subscribe\", \"args\": [\"liquidation." + Constants.CURRENCY_PAIR + "\"]}");

            pingExecutor = Executors.newSingleThreadScheduledExecutor();
            pingExecutor.scheduleAtFixedRate(() -> {
                if (wsSession != null && wsSession.isOpen()) {
                    wsSession.getAsyncRemote().sendText("{\"op\": \"ping\"}");
                }
            }, 0, Constants.WS_PING_INTERVAL, TimeUnit.SECONDS);
            System.out.println("WebSocket connected and subscribed to liquidations.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @OnMessage
    public void onMessage(String message) {
        JSONObject json = new JSONObject(message);
        if (json.has("data") && json.getJSONObject("data").has("ts")) {
            JSONObject data = json.getJSONObject("data");
            long timestamp = data.getLong("ts");
            String side = data.getString("side").equals("Buy") ? "short" : "long";
            double qty = data.getDouble("qty");
            dbManager.saveLiquidation(timestamp, side, qty);
            System.out.println("Liquidation: " + side + " " + qty + " at " + timestamp);
        } else {
            System.out.println("WebSocket message: " + message);
        }
    }

    @OnError
    public void onError(Throwable t) {
        t.printStackTrace();
        reconnect();
    }

    @OnClose
    public void onClose() {
        System.out.println("WebSocket closed. Reconnecting...");
        reconnect();
    }

    private void reconnect() {
        try {
            Thread.sleep(5000);
            connectWebSocket();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void closeWebSocket() {
        if (pingExecutor != null && !pingExecutor.isShutdown()) {
            pingExecutor.shutdownNow();
        }
        if (wsSession != null && wsSession.isOpen()) {
            try {
                wsSession.close();
                System.out.println("WebSocket closed manually.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Scheduled(fixedRate = Constants.PREDICTION_INTERVAL)
    public void predictAndBroadcast() {
        List<Candle> candles = dbManager.getCandles(50);
        if (!candles.isEmpty()) {
            double[] input = getLatestInput(candles.get(0));
            double prediction = neuralNetwork.predict(input);
            webSocketHandler.broadcastPrediction(prediction);
            predictionController.updatePrediction(prediction);
            System.out.println("Predicted price: " + prediction);
        }
    }

    private double[] getLatestInput(Candle latestCandle) {
        try (var conn = dbManager.getConnection();
             var stmt = conn.prepareStatement("SELECT sma, rsi, stochastic_k, stochastic_d FROM indicators WHERE timestamp = ?")) {
            stmt.setLong(1, latestCandle.getTimestamp());
            ResultSet rs = stmt.executeQuery();
            double sma = rs.next() ? rs.getDouble("sma") : 0.0;
            double rsi = rs.getDouble("rsi");
            double stochasticK = rs.getDouble("stochastic_k");
            double stochasticD = rs.getDouble("stochastic_d");
            double imbalanceInfluence = imbalanceZones.getImbalanceInfluence(latestCandle.getClose());
            double liquidationInfluence = getLiquidationInfluence(latestCandle.getTimestamp());

            return new double[]{
                    latestCandle.getOpen(), latestCandle.getHigh(), latestCandle.getLow(), latestCandle.getClose(),
                    latestCandle.getVolume(), sma, rsi, stochasticK, stochasticD, imbalanceInfluence + liquidationInfluence
            };
        } catch (SQLException e) {
            e.printStackTrace();
            return new double[10];
        }
    }

    private double getLiquidationInfluence(long timestamp) {
        try (var conn = dbManager.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT SUM(CASE WHEN side = 'long' THEN qty ELSE 0 END) as long_qty, " +
                             "SUM(CASE WHEN side = 'short' THEN qty ELSE 0 END) as short_qty " +
                             "FROM liquidations WHERE timestamp > ? AND timestamp <= ?")) {
            stmt.setLong(1, timestamp - 15 * 60 * 1000);
            stmt.setLong(2, timestamp);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                double longQty = rs.getDouble("long_qty");
                double shortQty = rs.getDouble("short_qty");
                double maxQty = Math.max(getMaxLiquidationQty(), 1.0);
                return (longQty - shortQty) / maxQty;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0.0;
    }

    private double getMaxLiquidationQty() {
        try (var conn = dbManager.getConnection();
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MAX(qty) FROM liquidations WHERE timestamp > " + (System.currentTimeMillis() - Constants.TRAINING_PERIOD * 15 * 60 * 1000))) {
            return rs.next() ? rs.getDouble(1) : 1.0;
        } catch (SQLException e) {
            e.printStackTrace();
            return 1.0;
        }
    }
}