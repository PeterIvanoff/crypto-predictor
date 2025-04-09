package com.crypto;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public class PredictionWebSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = Collections.synchronizedSet(new HashSet<>());
    private double latestPrediction = 0.0;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        sendPrediction(session, latestPrediction);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
    }

    public void broadcastPrediction(double prediction) {
        this.latestPrediction = prediction;
        synchronized (sessions) {
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    sendPrediction(session, prediction);
                }
            }
        }
    }

    private void sendPrediction(WebSocketSession session, double prediction) {
        try {
            JSONObject message = new JSONObject();
            message.put("timestamp", System.currentTimeMillis());
            message.put("currencyPair", Constants.CURRENCY_PAIR);
            message.put("predictedPrice", prediction);
            session.sendMessage(new TextMessage(message.toString()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}