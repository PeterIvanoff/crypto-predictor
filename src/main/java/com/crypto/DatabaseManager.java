package com.crypto;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:crypto_data.db";

    public DatabaseManager() {
        initializeDatabase();
    }

    private void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            // Таблица свечей
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS candles (
                    timestamp INTEGER PRIMARY KEY,
                    open REAL,
                    high REAL,
                    low REAL,
                    close REAL,
                    volume REAL
                )
            """);
            // Таблица индикаторов
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS indicators (
                    timestamp INTEGER PRIMARY KEY,
                    sma REAL,
                    rsi REAL,
                    stochastic_k REAL,
                    stochastic_d REAL
                )
            """);
            // Таблица ликвидаций
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS liquidations (
                    timestamp INTEGER,
                    side TEXT,
                    qty REAL
                )
            """);
            // Таблица зон имбаланса
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS imbalance_zones (
                    timestamp INTEGER,
                    price_level REAL,
                    strength REAL
                )
            """);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveCandle(long timestamp, double open, double high, double low, double close, double volume) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO candles (timestamp, open, high, low, close, volume) VALUES (?, ?, ?, ?, ?, ?)")) {
            pstmt.setLong(1, timestamp);
            pstmt.setDouble(2, open);
            pstmt.setDouble(3, high);
            pstmt.setDouble(4, low);
            pstmt.setDouble(5, close);
            pstmt.setDouble(6, volume);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveIndicators(long timestamp, double sma, double rsi, double stochasticK, double stochasticD) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO indicators (timestamp, sma, rsi, stochastic_k, stochastic_d) VALUES (?, ?, ?, ?, ?)")) {
            pstmt.setLong(1, timestamp);
            pstmt.setDouble(2, sma);
            pstmt.setDouble(3, rsi);
            pstmt.setDouble(4, stochasticK);
            pstmt.setDouble(5, stochasticD);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveLiquidation(long timestamp, String side, double qty) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO liquidations (timestamp, side, qty) VALUES (?, ?, ?)")) {
            pstmt.setLong(1, timestamp);
            pstmt.setString(2, side);
            pstmt.setDouble(3, qty);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveImbalanceZone(long timestamp, double priceLevel, double strength) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO imbalance_zones (timestamp, price_level, strength) VALUES (?, ?, ?)")) {
            pstmt.setLong(1, timestamp);
            pstmt.setDouble(2, priceLevel);
            pstmt.setDouble(3, strength);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<Candle> getCandles(int limit) {
        List<Candle> candles = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM candles ORDER BY timestamp DESC LIMIT " + limit)) {
            while (rs.next()) {
                candles.add(new Candle(
                        rs.getLong("timestamp"),
                        rs.getDouble("open"),
                        rs.getDouble("high"),
                        rs.getDouble("low"),
                        rs.getDouble("close"),
                        rs.getDouble("volume")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return candles;
    }

    public long getLastCandleTimestamp() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(timestamp) FROM candles")) {
            return rs.getLong(1);
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }
}