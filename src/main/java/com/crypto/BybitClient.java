package com.crypto;

import jakarta.annotation.PostConstruct;
import jakarta.websocket.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ClientEndpoint
public class BybitClient {
    private final DatabaseManager dbManager;
    private final Indicators indicators;
    private final ImbalanceZones imbalanceZones;
    private final NeuralNetwork neuralNetwork;
    private final PredictionWebSocketHandler webSocketHandler;
    private final PredictionController predictionController;

    private Session webSocketSession;

    public BybitClient(DatabaseManager dbManager, Indicators indicators, ImbalanceZones imbalanceZones,
                       NeuralNetwork neuralNetwork, PredictionWebSocketHandler webSocketHandler,
                       PredictionController predictionController) {
        this.dbManager = dbManager;
        this.indicators = indicators;
        this.imbalanceZones = imbalanceZones;
        this.neuralNetwork = neuralNetwork;
        this.webSocketHandler = webSocketHandler;
        this.predictionController = predictionController;
    }

    @PostConstruct
    public void init() {
        connectWebSocket();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(this::sendPing, 20, 20, TimeUnit.SECONDS);
        loadHistoricalData();
        indicators.calculate(); // Расчёт индикаторов
        imbalanceZones.calculate(); // Расчёт зон дисбаланса
    }

    private void sendPing() {
        if (webSocketSession != null && webSocketSession.isOpen()) {
            String pingMessage = "{\"op\":\"ping\"}";
            webSocketSession.getAsyncRemote().sendText(pingMessage);
            System.out.println("Sent ping to keep WebSocket alive.");
        }
    }

    public void loadHistoricalData() {
        HttpClient client = HttpClient.newHttpClient();
        int totalCandlesLoaded = 0;
        long now = System.currentTimeMillis();
        long currentStart = now - Constants.TRAINING_PERIOD * 15 * 60 * 1000;

        try (java.sql.Connection conn = dbManager.getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement("DELETE FROM candles")) {
            stmt.executeUpdate();
            System.out.println("Cleared candles table before loading new data.");
        } catch (java.sql.SQLException e) {
            e.printStackTrace();
        }

        while (totalCandlesLoaded < Constants.TRAINING_PERIOD) {
            String url = Constants.BYBIT_API_URL + "/v5/market/kline?category=linear&symbol=" + Constants.CURRENCY_PAIR +
                    "&interval=" + Constants.TIMEFRAME + "&start=" + currentStart + "&limit=200";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println("API Response: " + response.body());
                JSONObject json = new JSONObject(response.body());
                JSONArray result = json.getJSONObject("result").getJSONArray("list");

                if (result.length() == 0) {
                    System.out.println("No more candles to load at timestamp: " + currentStart);
                    break;
                }

                Set<Long> uniqueTimestamps = new HashSet<>();
                for (int i = result.length() - 1; i >= 0; i--) {
                    JSONArray candle = result.getJSONArray(i);
                    long timestamp = candle.getLong(0);
                    uniqueTimestamps.add(timestamp);
                    double open = candle.getDouble(1);
                    double high = candle.getDouble(2);
                    double low = candle.getDouble(3);
                    double close = candle.getDouble(4);
                    double volume = candle.getDouble(5);
                    dbManager.saveCandle(timestamp, open, high, low, close, volume);
                    totalCandlesLoaded++;
                    System.out.println("Saved candle: timestamp=" + timestamp + ", close=" + close);
                }
                System.out.println("Unique timestamps in this batch: " + uniqueTimestamps.size());
                System.out.println("Loaded " + totalCandlesLoaded + " candles so far...");

                currentStart = result.getJSONArray(0).getLong(0) + 15 * 60 * 1000;
            } catch (Exception e) {
                e.printStackTrace();
                break;
            }
        }
        System.out.println("Total loaded " + totalCandlesLoaded + " candles into database.");
    }

    private void connectWebSocket() {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(this, new URI(Constants.BYBIT_WS_URL));
        } catch (Exception e) {
            System.err.println("Failed to connect to WebSocket: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void subscribeToLiquidations() {
        if (webSocketSession != null && webSocketSession.isOpen()) {
            String subscriptionMessage = "{\"op\":\"subscribe\",\"args\":[\"liquidation.ETHUSDT\"]}";
            webSocketSession.getAsyncRemote().sendText(subscriptionMessage);
            System.out.println("Subscribed to liquidation.ETHUSDT");
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        this.webSocketSession = session;
        System.out.println("WebSocket session opened.");
        subscribeToLiquidations();
    }

    @OnMessage
    public void onMessage(String message) {
        System.out.println("WebSocket raw message: " + message);
        JSONObject json = new JSONObject(message);
        if (json.has("data") && json.getJSONObject("data").has("ts")) {
            JSONObject data = json.getJSONObject("data");
            long timestamp = data.getLong("ts");
            String side = data.getString("side").equals("Buy") ? "short" : "long";
            double qty = data.getDouble("size");
            dbManager.saveLiquidation(timestamp, side, qty);
            System.out.println("Liquidation: " + side + " " + qty + " at " + timestamp);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        this.webSocketSession = null;
        System.out.println("WebSocket closed: " + reason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("WebSocket error: " + throwable.getMessage());
        throwable.printStackTrace();
    }
}