package com.crypto;

import java.sql.DriverManager;
import java.util.List;

public class ImbalanceZones {
    private final DatabaseManager dbManager;

    public ImbalanceZones(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void calculateAndSaveZones() {
        List<Candle> candles = dbManager.getCandles(Constants.TRAINING_PERIOD);
        if (candles.size() < 2) return;

        double avgVolume = candles.stream().mapToDouble(Candle::getVolume).average().orElse(0.0);

        for (int i = 1; i < candles.size(); i++) {
            Candle current = candles.get(i);
            Candle previous = candles.get(i - 1);
            double gap = Math.abs(current.getOpen() - previous.getClose());
            if (gap > 0 && current.getVolume() > avgVolume * 1.5) {
                double priceLevel = (current.getHigh() + current.getLow()) / 2;
                double strength = Math.min(1.0, gap / current.getClose());
                dbManager.saveImbalanceZone(current.getTimestamp(), priceLevel, strength);
            }
        }
    }

    public double getImbalanceInfluence(double currentPrice) {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:crypto_data.db");
             var stmt = conn.prepareStatement("SELECT price_level, strength FROM imbalance_zones ORDER BY ABS(price_level - ?) LIMIT 1")) {
            stmt.setDouble(1, currentPrice);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                double priceLevel = rs.getDouble("price_level");
                double strength = rs.getDouble("strength");
                double distance = Math.abs(currentPrice - priceLevel);
                double maxDistance = currentPrice * 0.05;
                return strength * (1.0 - Math.min(distance / maxDistance, 1.0));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0.0;
    }
}