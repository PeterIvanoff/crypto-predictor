package com.crypto;

import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Component
public class Indicators {
    private final DatabaseManager dbManager;

    public Indicators(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void calculateAndSaveIndicators() {
        // Получаем все свечи из базы (или большее количество, чем 50, если нужно)
        List<Candle> candles = dbManager.getCandles(1000); // Увеличим до 1000, как в таблице
        if (candles.isEmpty()) {
            System.out.println("No candles available to calculate indicators.");
            return;
        }

        // Вычисляем индикаторы для каждой свечи, начиная с минимального индекса
        int minPeriod = Math.max(Math.max(Constants.SMA_PERIOD, Constants.RSI_PERIOD),
                Constants.STOCHASTIC_K_PERIOD + Constants.STOCHASTIC_K_SMOOTHING + Constants.STOCHASTIC_D_SMOOTHING - 2);
        if (candles.size() < minPeriod) {
            System.out.println("Not enough candles to calculate indicators: " + candles.size() + " < " + minPeriod);
            return;
        }

        for (int i = 0; i <= candles.size() - minPeriod; i++) {
            List<Candle> subList = candles.subList(i, i + minPeriod);
            double sma = calculateSMA(subList, Constants.SMA_PERIOD);
            double rsi = calculateRSI(subList, Constants.RSI_PERIOD);
            double[] stochastic = calculateStochastic(subList, Constants.STOCHASTIC_K_PERIOD,
                    Constants.STOCHASTIC_K_SMOOTHING, Constants.STOCHASTIC_D_SMOOTHING);

            long timestamp = subList.get(0).getTimestamp();
            dbManager.saveIndicators(timestamp, sma, rsi, stochastic[0], stochastic[1]);
            System.out.println("Calculated indicators for timestamp " + timestamp + ": SMA=" + sma + ", RSI=" + rsi + ", K=" + stochastic[0] + ", D=" + stochastic[1]);
        }
    }

    private double calculateSMA(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getClose();
        }
        return sum / period;
    }

    private double calculateRSI(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        double gain = 0, loss = 0;
        for (int i = 0; i < period - 1; i++) {
            double change = candles.get(i).getClose() - candles.get(i + 1).getClose();
            if (change > 0) gain += change;
            else loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        if (avgLoss == 0) return 100; // Если нет потерь, RSI = 100
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private double[] calculateStochastic(List<Candle> candles, int kPeriod, int kSmoothing, int dSmoothing) {
        if (candles.size() < kPeriod + kSmoothing + dSmoothing - 2) return new double[]{0, 0};

        // Расчёт %K
        List<Double> kValues = new ArrayList<>();
        for (int i = 0; i <= candles.size() - kPeriod; i++) {
            double high = candles.get(i).getHigh();
            double low = candles.get(i).getLow();
            for (int j = 1; j < kPeriod; j++) {
                high = Math.max(high, candles.get(i + j).getHigh());
                low = Math.min(low, candles.get(i + j).getLow());
            }
            double close = candles.get(i).getClose();
            double k = high != low ? (close - low) / (high - low) * 100 : 50; // 50 как заглушка при high == low
            kValues.add(k);
        }

        // Сглаживание %K
        double kSmooth = 0;
        if (kValues.size() >= kSmoothing) {
            kSmooth = kValues.subList(0, kSmoothing).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }

        // Расчёт %D (сглаживание %K)
        double d = 0;
        if (kValues.size() >= kSmoothing + dSmoothing - 1) {
            List<Double> kSmoothValues = new ArrayList<>();
            for (int i = 0; i <= kValues.size() - kSmoothing; i++) {
                kSmoothValues.add(kValues.subList(i, i + kSmoothing).stream().mapToDouble(Double::doubleValue).average().orElse(0));
            }
            if (kSmoothValues.size() >= dSmoothing) {
                d = kSmoothValues.subList(0, dSmoothing).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            }
        }

        return new double[]{kSmooth, d};
    }
}