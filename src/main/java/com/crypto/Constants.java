package com.crypto;

public class Constants {
    public static String CURRENCY_PAIR = "ETHUSDT";
    public static String TIMEFRAME = "15"; // 15 минут
    public static int TRAINING_PERIOD = 1000; // Количество свечей для обучения
    public static int SMA_PERIOD = 20;
    public static int RSI_PERIOD = 14;
    public static int STOCHASTIC_K_PERIOD = 14;
    public static int STOCHASTIC_D_PERIOD = 3;
    public static int STOCHASTIC_SMOOTHING = 3;
    public static String BYBIT_API_URL = "https://api.bybit.com";
    public static String BYBIT_WS_URL = "wss://stream.bybit.com/v5/public/linear";
    public static int WS_PING_INTERVAL = 20; // Пинг каждые 20 секунд
}