package com.crypto;

import jakarta.websocket.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ClientEndpoint
public class BybitClient {
    private final DatabaseManager dbManager;
    private Session wsSession;
    private final Indicators indicators;
    private final ImbalanceZones imbalanceZones;

    public BybitClient(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.indicators = new Indicators(dbManager);
        this.imbalanceZones = new ImbalanceZones(dbManager);
        loadHistoricalData();
        indicators.calculateAndSaveIndicators();
        imbalanceZones.calculateAndSaveZones();
        connectWebSocket();
    }

    public void loadHistoricalData() {
        long lastTimestamp = dbManager.getLastCandleTimestamp();
        long now = System.currentTimeMillis();
        if (lastTimestamp == 0) lastTimestamp = now - Constants.TRAINING_PERIOD * 15 * 60 * 1000;

        String url = Constants.BYBIT_API_URL + "/v5/market/kline?category=linear&symbol=" + Constants.CURRENCY_PAIR +
                "&interval=" + Constants.TIMEFRAME + "&start=" + lastTimestamp + "&end=" + now;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(response.body());
            JSONArray result = json.getJSONObject("result").getJSONArray("list");

            for (int i = result.length() - 1; i >= 0; i--) {
                JSONArray candle = result.getJSONArray(i);
                long timestamp = candle.getLong(0);
                double open = candle.getDouble(1);
                double high = candle.getDouble(2);
                double low = candle.getDouble(3);
                double close = candle.getDouble(4);
                double volume = candle.getDouble(5);
                dbManager.saveCandle(timestamp, open, high, low, close, volume);
            }
            System.out.println("Loaded " + result.length() + " candles into database.");
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void connectWebSocket() {
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            wsSession = container.connectToServer(this, new URI(Constants.BYBIT_WS_URL));

            wsSession.getAsyncRemote().sendText("{\"op\": \"subscribe\", \"args\": [\"liquidation." + Constants.CURRENCY_PAIR + "\"]}");

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(() -> {
                if (wsSession.isOpen()) {
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
        if (json.has("data")) {
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
}