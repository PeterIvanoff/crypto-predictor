package com.crypto;

import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class NeuralNetwork {
    private final DatabaseManager dbManager;
    private final Indicators indicators;
    private final ImbalanceZones imbalanceZones;
    private MultiLayerNetwork model;

    public NeuralNetwork(DatabaseManager dbManager, Indicators indicators, ImbalanceZones imbalanceZones) {
        this.dbManager = dbManager;
        this.indicators = indicators;
        this.imbalanceZones = imbalanceZones;
        initializeModel();
        trainModel();
    }

    private void initializeModel() {
        int inputSize = 10; // open, high, low, close, volume, sma, rsi, stochastic_k, stochastic_d, imbalance_influence
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(123)
                .updater(new Adam(0.001))
                .list()
                .layer(new DenseLayer.Builder().nIn(inputSize).nOut(64).activation(Activation.RELU).build())
                .layer(new DenseLayer.Builder().nIn(64).nOut(32).activation(Activation.RELU).build())
                .layer(new OutputLayer.Builder(LossFunctions.LossFunction.MSE).nIn(32).nOut(1).activation(Activation.IDENTITY).build())
                .build();

        model = new MultiLayerNetwork(conf);
        model.init();
        model.setListeners(new ScoreIterationListener(100));
    }

    private void trainModel() {
        List<Candle> candles = dbManager.getCandles(Constants.TRAINING_PERIOD);
        if (candles.size() < Constants.SMA_PERIOD) return;

        INDArray features = Nd4j.zeros(candles.size() - 1, 10);
        INDArray labels = Nd4j.zeros(candles.size() - 1, 1);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:crypto_data.db")) {
            for (int i = 0; i < candles.size() - 1; i++) {
                Candle candle = candles.get(i);
                double imbalanceInfluence = imbalanceZones.getImbalanceInfluence(candle.getClose());

                var stmt = conn.prepareStatement("SELECT sma, rsi, stochastic_k, stochastic_d FROM indicators WHERE timestamp = ?");
                stmt.setLong(1, candle.getTimestamp());
                ResultSet rs = stmt.executeQuery();
                double sma = rs.next() ? rs.getDouble("sma") : 0.0;
                double rsi = rs.getDouble("rsi");
                double stochasticK = rs.getDouble("stochastic_k");
                double stochasticD = rs.getDouble("stochastic_d");

                double liquidationInfluence = getLiquidationInfluence(candle.getTimestamp());

                features.putRow(i, Nd4j.create(new double[]{
                        candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume(),
                        sma, rsi, stochasticK, stochasticD, imbalanceInfluence + liquidationInfluence
                }));
                labels.putRow(i, Nd4j.create(new double[]{candles.get(i + 1).getClose()}));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        model.fit(features, labels);
    }

    private double getLiquidationInfluence(long timestamp) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:crypto_data.db");
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
                return (longQty - shortQty) / maxQty; // Положительное = рост, отрицательное = падение
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0.0;
    }

    private double getMaxLiquidationQty() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:crypto_data.db");
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MAX(qty) FROM liquidations WHERE timestamp > " + (System.currentTimeMillis() - Constants.TRAINING_PERIOD * 15 * 60 * 1000))) {
            return rs.next() ? rs.getDouble(1) : 1.0;
        } catch (SQLException e) {
            e.printStackTrace();
            return 1.0;
        }
    }

    public double predict(double[] input) {
        INDArray inputArray = Nd4j.create(input).reshape(1, input.length);
        return model.output(inputArray).getDouble(0);
    }
}