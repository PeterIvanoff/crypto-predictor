package com.crypto;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main extends Application {
    private DatabaseManager dbManager;
    private BybitClient bybitClient;
    private Indicators indicators;
    private ImbalanceZones imbalanceZones;
    private NeuralNetwork neuralNetwork;
    private LineChart<String, Number> candleChart;
    private BarChart<String, Number> liquidationChart;

    @Override
    public void start(Stage primaryStage) {
        dbManager = new DatabaseManager();
        bybitClient = new BybitClient(dbManager);
        indicators = new Indicators(dbManager);
        imbalanceZones = new ImbalanceZones(dbManager);
        neuralNetwork = new NeuralNetwork(dbManager, indicators, imbalanceZones);

        VBox root = new VBox(10);
        ComboBox<String> pairSelector = new ComboBox<>();
        pairSelector.getItems().addAll("ETHUSDT", "BTCUSDT", "XRPUSDT");
        pairSelector.setValue(Constants.CURRENCY_PAIR);
        pairSelector.setOnAction(e -> {
            Constants.CURRENCY_PAIR = pairSelector.getValue();
            bybitClient.loadHistoricalData();
            updateCharts();
        });

        candleChart = createCandleChart();
        liquidationChart = createLiquidationChart();
        root.getChildren().addAll(pairSelector, candleChart, liquidationChart);

        Scene scene = new Scene(root, 800, 600);
        primaryStage.setTitle("Crypto Price Predictor");
        primaryStage.setScene(scene);
        primaryStage.show();

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> Platform.runLater(this::updateCharts), 0, 15, TimeUnit.MINUTES);
    }

    private LineChart<String, Number> createCandleChart() {
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        LineChart<String, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Candlestick Chart with Imbalance Zones");
        updateCandleChart(chart);
        return chart;
    }

    private BarChart<String, Number> createLiquidationChart() {
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setTitle("Liquidations (Long above, Short below)");
        updateLiquidationChart(chart);
        return chart;
    }

    private void updateCharts() {
        updateCandleChart(candleChart);
        updateLiquidationChart(liquidationChart);
    }

    private void updateCandleChart(LineChart<String, Number> chart) {
        chart.getData().clear();
        List<Candle> candles = dbManager.getCandles(50);
        if (candles.isEmpty()) return;

        XYChart.Series<String, Number> closeSeries = new XYChart.Series<>();
        closeSeries.setName("Close Price");

        double lastClose = candles.get(0).getClose();
        for (Candle candle : candles) {
            String time = String.valueOf(candle.getTimestamp());
            closeSeries.getData().add(new XYChart.Data<>(time, candle.getClose()));
        }

        double[] input = getLatestInput(candles.get(0));
        double predictedPrice = neuralNetwork.predict(input);
        closeSeries.getData().add(new XYChart.Data<>(String.valueOf(System.currentTimeMillis()), predictedPrice));

        chart.getData().add(closeSeries);

        double movement = Math.abs(predictedPrice - lastClose) / lastClose;
        if (movement > 0.02) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Price Movement Alert");
                alert.setContentText("Predicted price change: " + String.format("%.2f%%", movement * 100));
                alert.show();
            });
        }
    }

    private void updateLiquidationChart(BarChart<String, Number> chart) {
        chart.getData().clear();
        try (var conn = DriverManager.getConnection("jdbc:sqlite:crypto_data.db");
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT timestamp, side, qty FROM liquidations ORDER BY timestamp DESC LIMIT 50")) {
            XYChart.Series<String, Number> longSeries = new XYChart.Series<>();
            longSeries.setName("Long Liquidations");
            XYChart.Series<String, Number> shortSeries = new XYChart.Series<>();
            shortSeries.setName("Short Liquidations");

            while (rs.next()) {
                String time = String.valueOf(rs.getLong("timestamp"));
                double qty = rs.getDouble("qty");
                if (rs.getString("side").equals("long")) {
                    longSeries.getData().add(new XYChart.Data<>(time, qty));
                } else {
                    shortSeries.getData().add(new XYChart.Data<>(time, -qty));
                }
            }
            chart.getData().addAll(longSeries, shortSeries);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private double[] getLatestInput(Candle latestCandle) {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:crypto_data.db");
             var stmt = conn.prepareStatement("SELECT sma, rsi, stochastic_k, stochastic_d FROM indicators WHERE timestamp = ?")) {
            stmt.setLong(1, latestCandle.getTimestamp());
            ResultSet rs = stmt.executeQuery();
            double sma = rs.next() ? rs.getDouble("sma") : 0.0;
            double rsi = rs.getDouble("rsi");
            double stochasticK = rs.getDouble("stochastic_k");
            double stochasticD = rs.getDouble("stochastic_d");
            double imbalanceInfluence = imbalanceZones.getImbalanceInfluence(latestCandle.getClose());
            double liquidationInfluence = getLiquidationInfluence(latestCandle.getTimestamp());

            return new double[]{
                    latestCandle.getOpen(), latestCandle.getHigh(), latestCandle.getLow(), latestCandle.getClose(),
                    latestCandle.getVolume(), sma, rsi, stochasticK, stochasticD, imbalanceInfluence + liquidationInfluence
            };
        } catch (SQLException e) {
            e.printStackTrace();
            return new double[10];
        }
    }

    private double getLiquidationInfluence(long timestamp) {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:crypto_data.db");
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
        try (var conn = DriverManager.getConnection("jdbc:sqlite:crypto_data.db");
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MAX(qty) FROM liquidations WHERE timestamp > " + (System.currentTimeMillis() - Constants.TRAINING_PERIOD * 15 * 60 * 1000))) {
            return rs.next() ? rs.getDouble(1) : 1.0;
        } catch (SQLException e) {
            e.printStackTrace();
            return 1.0;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}