package com.crypto;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Indicators {
    private final DatabaseManager dbManager;

    public Indicators(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void calculateAndSaveIndicators() {
        List<Candle> candles = dbManager.getCandles(Constants.TRAINING_PERIOD);
        if (candles.isEmpty()) {
            System.out.println("No candles available to calculate indicators.");
            return;
        }

        int minPeriod = Math.max(Math.max(Constants.SMA_PERIOD, Constants.RSI_PERIOD),
                Math.max(Constants.STOCHASTIC_K_PERIOD + Constants.STOCHASTIC_K_SMOOTHING + Constants.STOCHASTIC_D_SMOOTHING - 2,
                        Constants.STOCH_RSI_PERIOD + Constants.STOCH_RSI_K_SMOOTHING + Constants.STOCH_RSI_D_SMOOTHING + Constants.STOCH_RSI_D_SMOOTHING - 3));
        if (candles.size() < minPeriod) {
            System.out.println("Not enough candles to calculate indicators: " + candles.size() + " < " + minPeriod);
            return;
        }

        for (int i = minPeriod - 1; i < candles.size(); i++) {
            List<Candle> subList = candles.subList(i - minPeriod + 1, i + 1);
            double sma = calculateSMA(subList, Constants.SMA_PERIOD);
            double rsi = calculateRSI(subList, Constants.RSI_PERIOD);
            double[] stochastic = calculateStochastic(subList, Constants.STOCHASTIC_K_PERIOD,
                    Constants.STOCHASTIC_K_SMOOTHING, Constants.STOCHASTIC_D_SMOOTHING);
            double[] stochRSI = calculateStochRSI(subList, Constants.STOCH_RSI_PERIOD,
                    Constants.STOCH_RSI_K_SMOOTHING, Constants.STOCH_RSI_D_SMOOTHING);

            long timestamp = subList.get(subList.size() - 1).getTimestamp();
            dbManager.saveIndicators(timestamp, sma, rsi, stochastic[0], stochastic[1], stochRSI[0], stochRSI[1]);
//            System.out.println("Saved indicators for timestamp " + timestamp + ": SMA=" + sma + ", RSI=" + rsi +
//                    ", StochK=" + stochastic[0] + ", StochD=" + stochastic[1] +
//                    ", StochRSI_K=" + stochRSI[0] + ", StochRSI_D=" + stochRSI[1]);
        }
    }

    private double calculateSMA(List<Candle> candles, int period) {
        if (candles.size() < period) return 0.0;
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getClose();
        }
        return sum / period;
    }

    private double calculateRSI(List<Candle> candles, int period) {
        if (candles.size() < period) return 0.0;
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
        if (candles.size() < kPeriod + kSmoothing + dSmoothing - 2) return new double[]{0.0, 0.0};

        List<Double> kValues = new ArrayList<>();
        for (int i = 0; i <= candles.size() - kPeriod; i++) {
            List<Candle> window = candles.subList(i, i + kPeriod);
            double highestHigh = window.stream().mapToDouble(Candle::getHigh).max().orElse(0);
            double lowestLow = window.stream().mapToDouble(Candle::getLow).min().orElse(0);
            double currentClose = window.get(0).getClose();
            double k = highestHigh != lowestLow ?
                    (currentClose - lowestLow) / (highestHigh - lowestLow) * 100 : 50;
            kValues.add(k);
        }

        double kSmooth = 0;
        if (kValues.size() >= kSmoothing) {
            kSmooth = kValues.subList(kValues.size() - kSmoothing, kValues.size()).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0);
        }

        double d = 0;
        if (kValues.size() >= kSmoothing) {
            List<Double> kSmoothValues = new ArrayList<>();
            for (int i = 0; i <= kValues.size() - kSmoothing; i++) {
                kSmoothValues.add(kValues.subList(i, i + kSmoothing).stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0));
            }
            if (kSmoothValues.size() >= dSmoothing) {
                d = kSmoothValues.subList(kSmoothValues.size() - dSmoothing, kSmoothValues.size()).stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0);
            }
        }

        return new double[]{kSmooth, d};
    }

    private double[] calculateStochRSI(List<Candle> candles, int rsiPeriod, int kSmoothing, int dSmoothing) {
        if (candles.size() < rsiPeriod) return new double[]{0.0, 0.0};

        List<Double> rsiValues = new ArrayList<>();
        for (int i = 0; i <= candles.size() - rsiPeriod; i++) {
            List<Candle> rsiSubList = candles.subList(i, i + rsiPeriod);
            double rsi = calculateRSI(rsiSubList, rsiPeriod);
            rsiValues.add(rsi);
        }

        List<Double> kValues = new ArrayList<>();
        for (int i = 0; i <= rsiValues.size() - kSmoothing; i++) {
            List<Double> rsiWindow = rsiValues.subList(i, i + kSmoothing);
            double lowestRSI = rsiWindow.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double highestRSI = rsiWindow.stream().mapToDouble(Double::doubleValue).max().orElse(100);
            double currentRSI = rsiValues.get(i);
            double k = highestRSI != lowestRSI ?
                    (currentRSI - lowestRSI) / (highestRSI - lowestRSI) * 100 : 50;
            kValues.add(k);
        }

        double kSmooth = 0;
        if (kValues.size() >= kSmoothing) {
            kSmooth = kValues.subList(kValues.size() - kSmoothing, kValues.size()).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0);
        } else if (!kValues.isEmpty()) {
            kSmooth = kValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }

        double d = 0;
        if (kValues.size() >= kSmoothing) {
            List<Double> kSmoothValues = new ArrayList<>();
            for (int i = 0; i <= kValues.size() - kSmoothing; i++) {
                kSmoothValues.add(kValues.subList(i, i + kSmoothing).stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0));
            }
            if (kSmoothValues.size() >= dSmoothing) {
                d = kSmoothValues.subList(kSmoothValues.size() - dSmoothing, kSmoothValues.size()).stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0);
            } else if (!kSmoothValues.isEmpty()) {
                d = kSmoothValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            }
        }

        return new double[]{kSmooth, d};
    }
}