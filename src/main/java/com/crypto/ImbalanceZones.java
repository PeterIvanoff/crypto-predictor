package com.crypto;

import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
public class ImbalanceZones {
    private final DatabaseManager dbManager;

    public ImbalanceZones(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void calculateAndSaveZones() {
        // Получаем все свечи (1000, как в таблице)
        List<Candle> candles = dbManager.getCandles(Constants.TRAINING_PERIOD);
        if (candles.isEmpty()) {
            System.out.println("No candles available to calculate imbalance zones.");
            return;
        }

        // Динамический порог объёма (например, средний объём * 2)
        double totalVolume = candles.stream().mapToDouble(Candle::getVolume).sum();
        double avgVolume = totalVolume / candles.size();
        double volumeThreshold = avgVolume * 2; // Порог — в 2 раза выше среднего
        //System.out.println("Calculated volume threshold: " + volumeThreshold);

        for (Candle candle : candles) {
            if (candle.getVolume() > volumeThreshold) {
                dbManager.saveImbalanceZone(candle.getTimestamp(), candle.getClose(), candle.getVolume());
//                System.out.println("Saved imbalance zone: timestamp=" + candle.getTimestamp() +
//                        ", price=" + candle.getClose() + ", volume=" + candle.getVolume());
            }
        }
        //System.out.println("Finished calculating and saving imbalance zones.");
    }

    public double getImbalanceInfluence(double currentPrice) {
        String sql = "SELECT price, volume FROM imbalance_zones ORDER BY timestamp DESC LIMIT 10";
        try (var conn = dbManager.getConnection();
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            double influence = 0;
            int count = 0;
            while (rs.next()) {
                double price = rs.getDouble("price");
                double volume = rs.getDouble("volume");
                double distance = Math.abs(currentPrice - price);
                influence += volume / (distance + 1); // Добавляем 1, чтобы избежать деления на 0
                count++;
            }
            if (count == 0) {
                System.out.println("No imbalance zones found for influence calculation.");
                return 0;
            }
            double normalizedInfluence = influence / 1000;
            //System.out.println("Calculated imbalance influence: " + normalizedInfluence + " for price=" + currentPrice);
            return normalizedInfluence;
        } catch (SQLException e) {
            System.err.println("Error calculating imbalance influence: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }
}