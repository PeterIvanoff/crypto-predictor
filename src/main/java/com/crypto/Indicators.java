package com.crypto;

import java.util.List;

public class Indicators {
    private final DatabaseManager dbManager;

    public Indicators(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void calculateAndSaveIndicators() {
        List<Candle> candles = dbManager.getCandles(Constants.TRAINING_PERIOD);
        if (candles.size() < Constants.SMA_PERIOD) return;

        for (int i = 0; i < candles.size(); i++) {
            long timestamp = candles.get(i).getTimestamp();
            double sma = calculateSMA(candles, i);
            double rsi = calculateRSI(candles, i);
            double[] stochastic = calculateStochastic(candles, i);
            dbManager.saveIndicators(timestamp, sma, rsi, stochastic[0], stochastic[1]);
        }
    }

    private double calculateSMA(List<Candle> candles, int index) {
        if (index + Constants.SMA_PERIOD > candles.size()) return 0.0;
        double sum = 0.0;
        for (int i = index; i < index + Constants.SMA_PERIOD; i++) {
            sum += candles.get(i).getClose();
        }
        return sum / Constants.SMA_PERIOD;
    }

    private double calculateRSI(List<Candle> candles, int index) {
        if (index + Constants.RSI_PERIOD > candles.size()) return 0.0;
        double gain = 0.0, loss = 0.0;
        for (int i = index; i < index + Constants.RSI_PERIOD - 1; i++) {
            double diff = candles.get(i).getClose() - candles.get(i + 1).getClose();
            if (diff > 0) gain += diff;
            else loss -= diff;
        }
        gain /= Constants.RSI_PERIOD;
        loss /= Constants.RSI_PERIOD;
        if (loss == 0) return 100.0;
        double rs = gain / loss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double[] calculateStochastic(List<Candle> candles, int index) {
        if (index + Constants.STOCHASTIC_K_PERIOD > candles.size()) return new double[]{0.0, 0.0};

        double highestHigh = candles.get(index).getHigh();
        double lowestLow = candles.get(index).getLow();
        for (int i = index; i < index + Constants.STOCHASTIC_K_PERIOD; i++) {
            highestHigh = Math.max(highestHigh, candles.get(i).getHigh());
            lowestLow = Math.min(lowestLow, candles.get(i).getLow());
        }
        double k = (candles.get(index).getClose() - lowestLow) / (highestHigh - lowestLow) * 100;

        double d = 0.0;
        int count = 0;
        for (int i = index; i < index + Constants.STOCHASTIC_D_PERIOD && i < candles.size(); i++) {
            highestHigh = candles.get(i).getHigh();
            lowestLow = candles.get(i).getLow();
            for (int j = i; j < i + Constants.STOCHASTIC_K_PERIOD && j < candles.size(); j++) {
                highestHigh = Math.max(highestHigh, candles.get(j).getHigh());
                lowestLow = Math.min(lowestLow, candles.get(j).getLow());
            }
            d += (candles.get(i).getClose() - lowestLow) / (highestHigh - lowestLow) * 100;
            count++;
        }
        d /= count;

        return new double[]{k, d};
    }
}