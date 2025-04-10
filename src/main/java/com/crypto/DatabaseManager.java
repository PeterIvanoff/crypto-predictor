package com.crypto;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Component
public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:crypto_data.db";
    private final Object lock = new Object(); // Объект для синхронизации

    public DatabaseManager() {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS candles (" +
                            "timestamp INTEGER PRIMARY KEY, open REAL, high REAL, low REAL, close REAL, volume REAL)");
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS indicators (" +
                            "timestamp INTEGER PRIMARY KEY, sma REAL, rsi REAL, " +
                            "stochastic_k REAL, stochastic_d REAL, " +
                            "stoch_rsi_k REAL, stoch_rsi_d REAL)");
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS imbalance_zones (" +
                            "timestamp INTEGER PRIMARY KEY, price REAL, volume REAL)");
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS liquidations (" +
                            "timestamp INTEGER, side TEXT, qty REAL)");
            System.out.println("Database tables initialized.");
        } catch (SQLException e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    public void saveCandle(long timestamp, double open, double high, double low, double close, double volume) {
        synchronized (lock) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT OR REPLACE INTO candles (timestamp, open, high, low, close, volume) VALUES (?, ?, ?, ?, ?, ?)")) {
                stmt.setLong(1, timestamp);
                stmt.setDouble(2, open);
                stmt.setDouble(3, high);
                stmt.setDouble(4, low);
                stmt.setDouble(5, close);
                stmt.setDouble(6, volume);
                int rowsAffected = stmt.executeUpdate();
                if (rowsAffected > 0) {
                   // System.out.println("Saved candle: timestamp=" + timestamp + ", close=" + close);
                }
            } catch (SQLException e) {
                System.err.println("Error saving candle: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void saveLiquidation(long timestamp, String side, double qty) {
        synchronized (lock) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO liquidations (timestamp, side, qty) VALUES (?, ?, ?)")) {
                stmt.setLong(1, timestamp);
                stmt.setString(2, side);
                stmt.setDouble(3, qty);
                stmt.executeUpdate();
                //System.out.println("Saved liquidation: " + side + " " + qty + " at " + timestamp);
            } catch (SQLException e) {
                System.err.println("Error saving liquidation: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void saveIndicators(long timestamp, double sma, double rsi, double stochasticK, double stochasticD,
                               double stochRsiK, double stochRsiD) {
        synchronized (lock) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT OR REPLACE INTO indicators (timestamp, sma, rsi, stochastic_k, stochastic_d, stoch_rsi_k, stoch_rsi_d) " +
                                 "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                stmt.setLong(1, timestamp);
                stmt.setDouble(2, sma);
                stmt.setDouble(3, rsi);
                stmt.setDouble(4, stochasticK);
                stmt.setDouble(5, stochasticD);
                stmt.setDouble(6, stochRsiK);
                stmt.setDouble(7, stochRsiD);
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public void saveImbalanceZone(long timestamp, double price, double volume) {
        synchronized (lock) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT OR REPLACE INTO imbalance_zones (timestamp, price, volume) VALUES (?, ?, ?)")) {
                stmt.setLong(1, timestamp);
                stmt.setDouble(2, price);
                stmt.setDouble(3, volume);
                int rowsAffected = stmt.executeUpdate();
                if (rowsAffected > 0) {
                   // System.out.println("Saved imbalance zone: timestamp=" + timestamp + ", price=" + price);
                }
            } catch (SQLException e) {
                System.err.println("Error saving imbalance zone: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public List<Candle> getCandles(int limit) {
        synchronized (lock) {
            List<Candle> candles = new ArrayList<>();
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT timestamp, open, high, low, close, volume FROM candles ORDER BY timestamp DESC LIMIT ?")) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        candles.add(new Candle(rs.getLong("timestamp"), rs.getDouble("open"), rs.getDouble("high"),
                                rs.getDouble("low"), rs.getDouble("close"), rs.getDouble("volume")));
                    }
                }
            } catch (SQLException e) {
                System.err.println("Error retrieving candles: " + e.getMessage());
                e.printStackTrace();
            }
            return candles;
        }
    }

    public long getLastCandleTimestamp() {
        synchronized (lock) {
            try (Connection conn = getConnection();
                 ResultSet rs = conn.createStatement().executeQuery("SELECT MAX(timestamp) FROM candles")) {
                return rs.next() ? rs.getLong(1) : 0;
            } catch (SQLException e) {
                System.err.println("Error retrieving last candle timestamp: " + e.getMessage());
                e.printStackTrace();
                return 0;
            }
        }
    }

    public Candle getLastCandle() {
        List<Candle> candles = getCandles(1);
        return candles.isEmpty() ? null : candles.get(0);
    }
}