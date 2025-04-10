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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private volatile boolean initialDataLoaded = false;

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
        loadCandles();
        indicators.calculateAndSaveIndicators(); // Сначала рассчитываем индикаторы
        imbalanceZones.calculateAndSaveZones();  // Затем зоны дисбаланса
        neuralNetwork.trainModel();              // Теперь обучение
        double predictedPrice = neuralNetwork.getPredictedPrice();
        webSocketHandler.broadcastPrediction(predictedPrice);

        initialDataLoaded = true;
        connectWebSocket();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(this::sendPing, 20, 20, TimeUnit.SECONDS);
    }

    private void sendPing() {
        if (webSocketSession != null && webSocketSession.isOpen()) {
            String pingMessage = "{\"op\":\"ping\"}";
            webSocketSession.getAsyncRemote().sendText(pingMessage);
            //ystem.out.println("Sent ping to keep WebSocket alive.");
        }
    }

    private void loadCandles() {
        long lastTimestamp = dbManager.getLastCandleTimestamp();
        long now = System.currentTimeMillis();

        if (lastTimestamp == 0) {
            System.out.println("Candles table is empty, loading initial " + Constants.TRAINING_PERIOD + " candles.");
            loadHistoricalData(now - Constants.TRAINING_PERIOD * getTimeframeMillis(Constants.TIMEFRAME), Constants.TRAINING_PERIOD);
        } else {
            System.out.println("Updating candles from last timestamp: " + lastTimestamp);
            loadHistoricalData(getNextStartTime(lastTimestamp, Constants.TIMEFRAME), -1);
        }

//        indicators.calculateAndSaveIndicators();
//        imbalanceZones.calculateAndSaveZones();
//        neuralNetwork.trainModel();
    }

        private void loadHistoricalData(long startTime, int limit) {
            HttpClient client = HttpClient.newHttpClient();
            int totalCandlesLoaded = 0;
            long currentStart = startTime;
            long now = System.currentTimeMillis();

            while ((limit == -1 && currentStart < now) || (limit > 0 && totalCandlesLoaded < limit)) {
                String url = Constants.BYBIT_API_URL + "/v5/market/kline?category=linear&symbol=" + Constants.CURRENCY_PAIR +
                        "&interval=" + Constants.TIMEFRAME + "&start=" + currentStart + "&limit=200";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    JSONObject json = new JSONObject(response.body());
                    JSONArray result = json.getJSONObject("result").getJSONArray("list");

                    if (result.length() == 0) {
                        System.out.println("No more candles to load at timestamp: " + currentStart);
                        break;
                    }

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
                    System.out.println("Loaded " + totalCandlesLoaded + " candles so far...");

                    currentStart = getNextStartTime(result.getJSONArray(0).getLong(0) ,
                            Constants.TIMEFRAME);
                } catch (Exception e) {
                    System.err.println("Error loading candles: " + e.getMessage());
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

    private void subscribeToCandles() {
        if (webSocketSession != null && webSocketSession.isOpen()) {
            String subscriptionMessage = "{\"op\":\"subscribe\",\"args\":[\"kline." + Constants.TIMEFRAME + "." + Constants.CURRENCY_PAIR + "\"]}";
            webSocketSession.getAsyncRemote().sendText(subscriptionMessage);
            System.out.println("Subscribed to kline." + Constants.TIMEFRAME + "." + Constants.CURRENCY_PAIR);
        }
    }

    @OnOpen
    public void onOpen(Session session) {
        this.webSocketSession = session;
        System.out.println("WebSocket session opened.");
        subscribeToLiquidations();
        subscribeToCandles();
    }

    @OnMessage
    public void onMessage(String message) {
        //System.out.println("WebSocket raw message: " + message);
        try {
            JSONObject json = new JSONObject(message);

            if (json.has("topic") && json.getString("topic").startsWith("liquidation")) {
                long timestamp = json.getLong("ts"); // Исправлено: берем "ts" из верхнего уровня
                JSONObject data = json.getJSONObject("data");
                String side = data.getString("side").equals("Buy") ? "short" : "long";
                double qty = data.getDouble("size");
                dbManager.saveLiquidation(timestamp, side, qty);
            }

            if (initialDataLoaded && json.has("topic") && json.getString("topic").startsWith("kline")) {
                JSONArray dataArray = json.getJSONArray("data");
                if (dataArray.length() > 0) {
                    JSONObject data = dataArray.getJSONObject(0);
                    long timestamp = data.getLong("start");
                    double open = data.getDouble("open");
                    double high = data.getDouble("high");
                    double low = data.getDouble("low");
                    double close = data.getDouble("close");
                    double volume = data.getDouble("volume");
                    Boolean confirm = data.getBoolean("confirm");

                    if (confirm) {
                        dbManager.saveCandle(timestamp, open, high, low, close, volume);
                        //System.out.println("New confirmed candle received: timestamp=" + timestamp + ", close=" + close + ", volume=" + volume);
                        printSortedValues(high, low, neuralNetwork.getPredictedPrice());
                        Candle currentCandle = new Candle(timestamp, open, high, low, close, volume);

                        indicators.calculateAndSaveIndicators();
                        imbalanceZones.calculateAndSaveZones();
                        neuralNetwork.trainModel();
                        double predictedPrice = neuralNetwork.getPredictedPrice();
                        webSocketHandler.broadcastPrediction(predictedPrice);
                    } else {
                        //System.out.println("Received unconfirmed candle: timestamp=" + timestamp + ", close=" + close + ", skipping processing.");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing WebSocket message: " + e.getMessage());
            System.out.println("Last raw message: " + message);
            e.printStackTrace();
        }
    }

    public static void printSortedValues(double high, double low, double predicted) {
        final String RESET = "\u001B[0m";
        final String RED = "\u001B[31m";
        final String GREEN = "\u001B[32m";
        final String BLUE = "\u001B[34m";

        Map<String, Double> values = new HashMap<>();
        values.put("High 🔺", high);
        values.put("Low  🔻", low);
        values.put("Predicted ▄", predicted);  // Кирпичик в виде ▄ для Predicted

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(values.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue())); // по убыванию

        System.out.println("=============================");
        for (Map.Entry<String, Double> entry : sorted) {
            String label = entry.getKey();
            double value = entry.getValue();
            String color;

            if (label.startsWith("High")) {
                color = GREEN;
            } else if (label.startsWith("Low")) {
                color = RED;
            } else {
                color = BLUE;
            }

            System.out.printf("%s%-16s: %.2f%s%n", color, label, value, RESET);
        }
        System.out.println("=============================");
    }

    public static long getTimeframeMillis(String timeframeStr) {
        int minutes;

        if (timeframeStr.endsWith("m")) {
            minutes = Integer.parseInt(timeframeStr.replace("m", ""));
        } else if (timeframeStr.endsWith("h")) {
            minutes = Integer.parseInt(timeframeStr.replace("h", "")) * 60;
        } else if (timeframeStr.endsWith("d")) {
            minutes = Integer.parseInt(timeframeStr.replace("d", "")) * 60 * 24;
        } else {
            // Если нет суффикса — считаем, что указано в минутах
            minutes = Integer.parseInt(timeframeStr);
        }

        return minutes * 60 * 1000L;
    }
    private static long getNextStartTime(long lastCandleTime, String timeframeStr) {
        int minutes;

        if (timeframeStr.endsWith("m")) {
            minutes = Integer.parseInt(timeframeStr.replace("m", ""));
        } else if (timeframeStr.endsWith("h")) {
            minutes = Integer.parseInt(timeframeStr.replace("h", "")) * 60;
        } else if (timeframeStr.endsWith("d")) {
            minutes = Integer.parseInt(timeframeStr.replace("d", "")) * 60 * 24;
        } else {
            // Если нет суффикса — по умолчанию считаем, что это минуты (например, "1", "15")
            minutes = Integer.parseInt(timeframeStr);
        }

        return lastCandleTime + minutes * 60 * 1000L;
    }
    @OnClose
    public void onClose(Session session, CloseReason reason) {
        this.webSocketSession = null;
        System.out.println("WebSocket closed: " + reason.getReasonPhrase());
        connectWebSocket();
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("WebSocket error: " + throwable.getMessage());
        throwable.printStackTrace();
    }
}