package com.crypto;

import jakarta.annotation.PostConstruct;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Component
public class NeuralNetwork {
    private final DatabaseManager dbManager;
    private final Indicators indicators;
    private final ImbalanceZones imbalanceZones;
    private MultiLayerNetwork model;
    private double maxPrice;
    private double predictedPrice;

    public NeuralNetwork(DatabaseManager dbManager, Indicators indicators, ImbalanceZones imbalanceZones) {
        this.dbManager = dbManager;
        this.indicators = indicators;
        this.imbalanceZones = imbalanceZones;
        initializeModel();
    }

    private void initializeModel() {
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(123)
                .updater(new Adam(0.001))
                .list()
                .layer(0, new DenseLayer.Builder().nIn(11).nOut(20).activation(Activation.RELU).build())  // Первый слой
                .layer(1, new DenseLayer.Builder().nIn(20).nOut(15).activation(Activation.RELU).build()) // Новый слой
                .layer(2, new DenseLayer.Builder().nIn(15).nOut(10).activation(Activation.RELU).build()) // Сдвинутый слой
                .layer(3, new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .nIn(10).nOut(1).activation(Activation.IDENTITY).build())                       // Выходной слой
                .build();
//.regularization(true).l2(0.0001) // Добавлено
        model = new MultiLayerNetwork(conf);
        model.init();
    }

    @PostConstruct
    public void init() {
        trainModel();
    }

    public void trainModel() {
        List<Candle> candles = dbManager.getCandles(Constants.TRAINING_PERIOD);

        if (candles.size() < 50) {
            System.out.println("Not enough data to train model: " + candles.size() + " candles available.");
            predictedPrice = 0.0;
            return;
        }

        INDArray inputs = Nd4j.create(candles.size() - 1, 11);
        INDArray outputs = Nd4j.create(candles.size() - 1, 1);

        maxPrice = candles.stream().mapToDouble(Candle::getClose).max().orElse(1.0);

        for (int i = 0; i < candles.size() - 1; i++) {
            Candle candle = candles.get(i);
            double[] input = getInputForCandle(candle);
            if (input.length != 11) {
                System.out.println("Error: Input array length is " + input.length + " instead of 11: " + Arrays.toString(input));
                return;
            }
            inputs.putRow(i, Nd4j.create(input));
            outputs.putScalar(i, 0, candles.get(i + 1).getClose());
        }

        inputs.divi(maxPrice);
        outputs.divi(maxPrice);
        System.out.println("Training...");
        int epochs = 200;
        for (int epoch = 0; epoch < epochs; epoch++) {
            model.fit(inputs, outputs);
        }
        System.out.println("Model trained with " + (candles.size() - 1) + " samples, maxPrice=" + maxPrice);

        if (!candles.isEmpty()) {
            Candle lastCandle = candles.get(candles.size() - 1);
            double[] lastInput = getInputForCandle(lastCandle);
            System.out.println("Last input for prediction: " + Arrays.toString(lastInput)); // Отладка
            predictedPrice = predict(lastInput);
        }
    }

    public double predict(double[] input) {
        if (input == null || input.length != 11) {
            System.out.println("Invalid input for prediction: " + Arrays.toString(input));
            return 0.0; // Возвращаем 0, но можно изменить логику
        }
        INDArray inputArray = Nd4j.create(input, new int[]{1, 11});
        inputArray.divi(maxPrice);
        INDArray output = model.output(inputArray);
        double predictedValue = output.getDouble(0) * maxPrice;
        System.out.println("Raw output: " + output.getDouble(0) + ", Predicted price: " + predictedValue);
        return predictedValue;
    }

    public double getPredictedPrice() {
        return predictedPrice;
    }

    public double[] getInputForCandle(Candle candle) {
        try (var conn = dbManager.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT sma, rsi, stochastic_k, stochastic_d, stoch_rsi_k, stoch_rsi_d FROM indicators WHERE timestamp = ?")) {
            stmt.setLong(1, candle.getTimestamp());
            ResultSet rs = stmt.executeQuery();
            double sma = rs.next() ? rs.getDouble("sma") : 0.0;
            double rsi = rs.getDouble("rsi");
            double stochasticK = rs.getDouble("stochastic_k");
            double stochasticD = rs.getDouble("stochastic_d");
            double stochRsiK = rs.getDouble("stoch_rsi_k");
            double stochRsiD = rs.getDouble("stoch_rsi_d");
            double imbalanceInfluence = imbalanceZones.getImbalanceInfluence(candle.getClose());
            double liquidationInfluence = getLiquidationInfluence(candle.getTimestamp());

            return new double[]{
                    candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(),
                    candle.getVolume(), sma, rsi, stochasticK, stochasticD,
                    stochRsiK, stochRsiD
            }; // 11 элементов
        } catch (SQLException e) {
            e.printStackTrace();
            return new double[11];
        }
    }

    private double getLiquidationInfluence(long timestamp) {
        try (var conn = dbManager.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT SUM(CASE WHEN side = 'long' THEN qty ELSE 0 END) as long_qty, " +
                             "SUM(CASE WHEN side = 'short' THEN qty ELSE 0 END) as short_qty " +
                             "FROM liquidations WHERE timestamp > ? AND timestamp <= ?")) {
            stmt.setLong(1, timestamp - BybitClient.getTimeframeMillis(Constants.TIMEFRAME));
            stmt.setLong(2, timestamp);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                double longQty = rs.getDouble("long_qty");
                double shortQty = rs.getDouble("short_qty");
                double maxQty = Math.max(getMaxLiquidationQty(), 1.0);
                return (longQty - shortQty) / maxQty;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0.0;
    }

    private double getMaxLiquidationQty() {
        try (var conn = dbManager.getConnection();
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MAX(qty) FROM liquidations WHERE timestamp > " + (System.currentTimeMillis() - Constants.TRAINING_PERIOD * BybitClient.getTimeframeMillis(Constants.TIMEFRAME)))) {
            return rs.next() ? rs.getDouble(1) : 1.0;
        } catch (SQLException e) {
            e.printStackTrace();
            return 1.0;
        }
    }
}