package com.crypto;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class ImbalanceZones {
    private final DatabaseManager dbManager;

    public ImbalanceZones(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void calculateAndSaveZones() {
        List<Candle> candles = dbManager.getCandles(50);
        if (candles.isEmpty()) return;

        for (Candle candle : candles) {
            double volumeThreshold = 1000; // Пример порога, можно вынести в Constants
            if (candle.getVolume() > volumeThreshold) {
                dbManager.saveImbalanceZone(candle.getTimestamp(), candle.getClose(), candle.getVolume());
            }
        }
    }

    public double getImbalanceInfluence(double currentPrice) {
        try (var conn = dbManager.getConnection();
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT price, volume FROM imbalance_zones ORDER BY timestamp DESC LIMIT 10")) {
            double influence = 0;
            while (rs.next()) {
                double price = rs.getDouble("price");
                double volume = rs.getDouble("volume");
                double distance = Math.abs(currentPrice - price);
                influence += volume / (distance + 1); // Простая формула влияния
            }
            return influence / 1000; // Нормализация
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }
}