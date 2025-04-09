package com.crypto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class Indicators {
    private final DatabaseManager dbManager;

    public Indicators(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void calculateAndSaveIndicators() {
        List<Candle> candles = dbManager.getCandles(50);
        if (candles.size() < Constants.SMA_PERIOD) return; // Минимальный размер для SMA

        double sma = calculateSMA(candles, Constants.SMA_PERIOD);
        double rsi = calculateRSI(candles, Constants.RSI_PERIOD);
        double[] stochastic = calculateStochastic(candles, Constants.STOCHASTIC_K_PERIOD,
                Constants.STOCHASTIC_K_SMOOTHING,
                Constants.STOCHASTIC_D_SMOOTHING);

        long latestTimestamp = candles.get(0).getTimestamp();
        dbManager.saveIndicators(latestTimestamp, sma, rsi, stochastic[0], stochastic[1]);
    }

    private double calculateSMA(List<Candle> candles, int period) {
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getClose();
        }
        return sum / period;
    }

    private double calculateRSI(List<Candle> candles, int period) {
        double gain = 0, loss = 0;
        for (int i = 0; i < period - 1; i++) {
            double change = candles.get(i).getClose() - candles.get(i + 1).getClose();
            if (change > 0) gain += change;
            else loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private double[] calculateStochastic(List<Candle> candles, int kPeriod, int kSmoothing, int dSmoothing) {
        double high = candles.get(0).getHigh();
        double low = candles.get(0).getLow();
        for (int i = 1; i < kPeriod; i++) {
            high = Math.max(high, candles.get(i).getHigh());
            low = Math.min(low, candles.get(i).getLow());
        }
        double k = (candles.get(0).getClose() - low) / (high - low) * 100;

        List<Double> kValues = new ArrayList<>();
        kValues.add(k);
        for (int i = 1; i < kSmoothing; i++) {
            if (i < candles.size()) {
                high = Math.max(high, candles.get(i).getHigh());
                low = Math.min(low, candles.get(i).getLow());
                k = (candles.get(i).getClose() - low) / (high - low) * 100;
                kValues.add(k);
            }
        }

        double kSmooth = kValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double d = kSmooth; // Упрощённый D для одного значения
        return new double[]{kSmooth, d};
    }
}