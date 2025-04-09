package com.crypto;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
public class NeuralNetwork {
    private final DatabaseManager dbManager;
    private final Indicators indicators;
    private final ImbalanceZones imbalanceZones;
    private MultiLayerNetwork model;
    private double maxPrice;

    public NeuralNetwork(DatabaseManager dbManager, Indicators indicators, ImbalanceZones imbalanceZones) {
        this.dbManager = dbManager;
        this.indicators = indicators;
        this.imbalanceZones = imbalanceZones;
        initializeModel();
        trainModel();
    }

    private void initializeModel() {
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(123)
                .updater(new Adam(0.001))
                .list()
                .layer(0, new DenseLayer.Builder().nIn(10).nOut(20).activation(Activation.RELU).build())
                .layer(1, new DenseLayer.Builder().nIn(20).nOut(10).activation(Activation.RELU).build())
                .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .nIn(10).nOut(1).activation(Activation.IDENTITY).build())
                .build();

        model = new MultiLayerNetwork(conf);
        model.init();
    }

    @Scheduled(fixedRate = Constants.PREDICTION_INTERVAL)
    private void trainModel() {
        List<Candle> candles = dbManager.getCandles(Constants.TRAINING_PERIOD);
        System.out.println("Candles retrieved for training: " + candles.size());
        if (candles.size() < 50) {
            System.out.println("Not enough data to train model: " + candles.size() + " candles available.");
            return;
        }

        INDArray inputs = Nd4j.create(candles.size() - 1, 10);
        INDArray outputs = Nd4j.create(candles.size() - 1, 1);

        maxPrice = candles.stream().mapToDouble(Candle::getClose).max().orElse(1.0);

        for (int i = 0; i < candles.size() - 1; i++) {
            Candle candle = candles.get(i);
            double[] input = getInputForCandle(candle);
            inputs.putRow(i, Nd4j.create(input));
            outputs.putScalar(i, 0, candles.get(i + 1).getClose());
        }

        inputs.divi(maxPrice);
        outputs.divi(maxPrice);

        int epochs = 100;
        for (int epoch = 0; epoch < epochs; epoch++) {
            model.fit(inputs, outputs);
            if (epoch % 10 == 0) {
                System.out.println("Epoch " + epoch + " completed");
            }
        }
        System.out.println("Model trained with " + (candles.size() - 1) + " samples, maxPrice=" + maxPrice);
    }

    public double predict(double[] input) {
        INDArray inputArray = Nd4j.create(input, new int[]{1, 10});
        inputArray.divi(maxPrice);
        INDArray output = model.output(inputArray);
        double predictedPrice = output.getDouble(0) * maxPrice;
        System.out.println("Raw output: " + output.getDouble(0) + ", Predicted price: " + predictedPrice);
        return predictedPrice;
    }

    private double[] getInputForCandle(Candle candle) {
        try (var conn = dbManager.getConnection();
             var stmt = conn.prepareStatement("SELECT sma, rsi, stochastic_k, stochastic_d FROM indicators WHERE timestamp = ?")) {
            stmt.setLong(1, candle.getTimestamp());
            ResultSet rs = stmt.executeQuery();
            double sma = rs.next() ? rs.getDouble("sma") : 0.0;
            double rsi = rs.getDouble("rsi");
            double stochasticK = rs.getDouble("stochastic_k");
            double stochasticD = rs.getDouble("stochastic_d");
            double imbalanceInfluence = imbalanceZones.getImbalanceInfluence(candle.getClose());
            double liquidationInfluence = getLiquidationInfluence(candle.getTimestamp());

            double[] input = new double[]{
                    candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(),
                    candle.getVolume(), sma, rsi, stochasticK, stochasticD, imbalanceInfluence + liquidationInfluence
            };
            System.out.println("Input for candle: " +
                    "Open=" + input[0] + ", High=" + input[1] + ", Low=" + input[2] + ", Close=" + input[3] +
                    ", Volume=" + input[4] + ", SMA=" + input[5] + ", RSI=" + input[6] +
                    ", StochK=" + input[7] + ", StochD=" + input[8] + ", Influence=" + input[9]);
            return input;
        } catch (SQLException e) {
            e.printStackTrace();
            return new double[10];
        }
    }

    private double getLiquidationInfluence(long timestamp) {
        try (var conn = dbManager.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT SUM(CASE WHEN side = 'long' THEN qty ELSE 0 END) as long_qty, " +
                             "SUM(CASE WHEN side = 'short' THEN qty ELSE 0 END) as short_qty " +
                             "FROM liquidations WHERE timestamp > ? AND timestamp <= ?")) {
            stmt.setLong(1, timestamp - 15 * 60 * 1000);
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
                     "SELECT MAX(qty) FROM liquidations WHERE timestamp > " + (System.currentTimeMillis() - Constants.TRAINING_PERIOD * 15 * 60 * 1000))) {
            return rs.next() ? rs.getDouble(1) : 1.0;
        } catch (SQLException e) {
            e.printStackTrace();
            return 1.0;
        }
    }
}