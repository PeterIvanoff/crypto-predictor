package com.crypto;

import jakarta.annotation.PostConstruct;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.LSTM;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
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
        if (Constants.MODEL_TYPE.equals("LSTM")) {
            initializeLSTMModel();
        } else if (Constants.MODEL_TYPE.equals("MLP")) {
            initializeMLPModel();
        } else {
            System.out.println("Unknown model type: " + Constants.MODEL_TYPE + ", defaulting to MLP");
            initializeMLPModel();
        }
    }

    private void initializeMLPModel() {
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(123)
                .updater(new Adam(0.001))
                .list()
                .layer(0, new DenseLayer.Builder().nIn(11).nOut(20).activation(Activation.RELU).build())
                .layer(1, new DenseLayer.Builder().nIn(20).nOut(15).activation(Activation.RELU).build())
                .layer(2, new DenseLayer.Builder().nIn(15).nOut(10).activation(Activation.RELU).build())
                .layer(3, new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .nIn(10).nOut(1).activation(Activation.IDENTITY).build())
                .build();
        model = new MultiLayerNetwork(conf);
        model.init();
        System.out.println("Initialized MLP model");
    }

    private void initializeLSTMModel() {
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(123)
                .updater(new Adam(Constants.LSTM_LEARNING_RATE))
                .list()
                .layer(0, new LSTM.Builder()
                        .nIn(Constants.LSTM_INPUT_SIZE)
                        .nOut(Constants.LSTM_HIDDEN_SIZE)
                        .activation(Activation.TANH)
                        .weightInit(WeightInit.XAVIER)
                        .build())
                .layer(1, new LSTM.Builder()
                        .nIn(Constants.LSTM_HIDDEN_SIZE)
                        .nOut(Constants.LSTM_HIDDEN_SIZE / 2)
                        .activation(Activation.TANH)
                        .weightInit(WeightInit.XAVIER)
                        .build())
                .layer(2, new RnnOutputLayer.Builder()
                        .nIn(Constants.LSTM_HIDDEN_SIZE / 2)
                        .nOut(Constants.LSTM_OUTPUT_SIZE)
                        .activation(Activation.IDENTITY)
                        .lossFunction(LossFunctions.LossFunction.MSE)
                        .weightInit(WeightInit.XAVIER)
                        .build())
                .build();
        model = new MultiLayerNetwork(conf);
        model.init();
        System.out.println("Initialized LSTM model");
    }

    @PostConstruct
    public void init() {
        try {
            //trainModel();
        } catch (Exception e) {
            System.err.println("Failed to train model during initialization: " + e.getMessage());
            e.printStackTrace();
            predictedPrice = 0.0;
        }
    }

    public void trainModel() {
        List<Candle> candles = dbManager.getCandles(Constants.TRAINING_PERIOD);

        if (candles.size() < Constants.LSTM_TIME_STEPS + 1) {
            System.out.println("Not enough data to train model: " + candles.size() + " candles available.");
            predictedPrice = 0.0;
            return;
        }

        maxPrice = candles.stream().mapToDouble(Candle::getClose).max().orElse(1.0);
        if (maxPrice == 0.0) {
            System.out.println("Max price is 0, cannot normalize data.");
            predictedPrice = 0.0;
            return;
        }

        if (Constants.MODEL_TYPE.equals("LSTM")) {
            trainLSTMModel(candles);
        } else {
            trainMLPModel(candles);
        }

        if (!candles.isEmpty()) {
            Candle lastCandle = candles.get(candles.size() - 1);
            double[] lastInput = getInputForCandle(lastCandle);
            System.out.println("Last input for prediction: " + Arrays.toString(lastInput));
            predictedPrice = predict(lastInput);
        }
    }

    private void trainMLPModel(List<Candle> candles) {
        INDArray inputs = Nd4j.create(candles.size() - 1, 11);
        INDArray outputs = Nd4j.create(candles.size() - 1, 1);

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
        System.out.println("Training MLP...");
        int epochs = 200;
        for (int epoch = 0; epoch < epochs; epoch++) {
            model.fit(inputs, outputs);
        }
        System.out.println("MLP model trained with " + (candles.size() - 1) + " samples, maxPrice=" + maxPrice);
    }

    private void trainLSTMModel(List<Candle> candles) {
        int numSamples = candles.size() - Constants.LSTM_TIME_STEPS;
        if (numSamples <= 0) {
            System.out.println("Not enough data for LSTM: " + candles.size() + " < " + (Constants.LSTM_TIME_STEPS + 1));
            predictedPrice = 0.0;
            return;
        }

        // Формируем входные данные с формой [numSamples, nIn, timeSteps]
        INDArray inputs = Nd4j.create(numSamples, Constants.LSTM_INPUT_SIZE, Constants.LSTM_TIME_STEPS);
        // Формируем метки с формой [numSamples, nOut, timeSteps]
        INDArray outputs = Nd4j.zeros(numSamples, Constants.LSTM_OUTPUT_SIZE, Constants.LSTM_TIME_STEPS);

        for (int i = 0; i < numSamples; i++) {
            for (int t = 0; t < Constants.LSTM_TIME_STEPS; t++) {
                Candle candle = candles.get(i + t);
                double[] input = getInputForCandle(candle);
                if (input.length != Constants.LSTM_INPUT_SIZE) {
                    System.out.println("Error: Input array length is " + input.length + " instead of " + Constants.LSTM_INPUT_SIZE);
                    return;
                }
                for (int f = 0; f < Constants.LSTM_INPUT_SIZE; f++) {
                    inputs.putScalar(new int[]{i, f, t}, input[f] / maxPrice);
                }
            }
            // Устанавливаем целевое значение только для последнего временного шага
            double targetPrice = candles.get(i + Constants.LSTM_TIME_STEPS).getClose() / maxPrice;
            outputs.putScalar(new int[]{i, 0, Constants.LSTM_TIME_STEPS - 1}, targetPrice);
        }

        System.out.println("Training LSTM...");
        DataSet dataSet = new DataSet(inputs, outputs);
        for (int epoch = 0; epoch < Constants.LSTM_EPOCHS; epoch++) {
            model.fit(dataSet);
        }
        System.out.println("LSTM model trained with " + numSamples + " samples, maxPrice=" + maxPrice);
    }

    public double predict(double[] input) {
        if (input == null || input.length != 11) {
            System.out.println("Invalid input for prediction: " + Arrays.toString(input));
            return 0.0;
        }

        if (Constants.MODEL_TYPE.equals("LSTM")) {
            return predictLSTM(input);
        } else {
            return predictMLP(input);
        }
    }

    private double predictMLP(double[] input) {
        INDArray inputArray = Nd4j.create(input, new int[]{1, 11});
        inputArray.divi(maxPrice);
        INDArray output = model.output(inputArray);
        double predictedValue = output.getDouble(0) * maxPrice;
        System.out.println("MLP - Raw output: " + output.getDouble(0) + ", Predicted price: " + predictedValue);
        return predictedValue;
    }

    private double predictLSTM(double[] input) {
        List<Candle> recentCandles = dbManager.getCandles(Constants.LSTM_TIME_STEPS);
        if (recentCandles.size() < Constants.LSTM_TIME_STEPS) {
            System.out.println("Not enough recent candles for LSTM prediction: " + recentCandles.size());
            return 0.0;
        }

        // Формируем входные данные с формой [1, nIn, timeSteps]
        INDArray inputArray = Nd4j.create(1, Constants.LSTM_INPUT_SIZE, Constants.LSTM_TIME_STEPS);
        for (int t = 0; t < Constants.LSTM_TIME_STEPS - 1; t++) {
            double[] pastInput = getInputForCandle(recentCandles.get(t));
            for (int f = 0; f < Constants.LSTM_INPUT_SIZE; f++) {
                inputArray.putScalar(new int[]{0, f, t}, pastInput[f] / maxPrice);
            }
        }
        for (int f = 0; f < Constants.LSTM_INPUT_SIZE; f++) {
            inputArray.putScalar(new int[]{0, f, Constants.LSTM_TIME_STEPS - 1}, input[f] / maxPrice);
        }

        INDArray output = model.output(inputArray);
        // Берем предсказание для последнего временного шага
        double predictedValue = output.getDouble(0, 0, Constants.LSTM_TIME_STEPS - 1) * maxPrice;
        System.out.println("LSTM - Raw output: " + output.getDouble(0, 0, Constants.LSTM_TIME_STEPS - 1) + ", Predicted price: " + predictedValue);
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
            };
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