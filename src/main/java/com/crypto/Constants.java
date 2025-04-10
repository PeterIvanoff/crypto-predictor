package com.crypto;

public class Constants {
    public static final String BYBIT_API_URL = "https://api.bybit.com";
    public static final String BYBIT_WS_URL = "wss://stream.bybit.com/v5/public/linear";
    public static final String CURRENCY_PAIR = "ETHUSDT";
    public static final String TIMEFRAME = "5";
    public static final int TRAINING_PERIOD = 500;
    public static final int WS_PING_INTERVAL = 30;
    public static final int SMA_PERIOD = 14;
    public static final int RSI_PERIOD = 14;
    public static final int STOCHASTIC_K_PERIOD = 14;
    public static final int STOCHASTIC_K_SMOOTHING = 3;
    public static final int STOCHASTIC_D_SMOOTHING = 3;
    public static final long PREDICTION_INTERVAL = 2 * 60 * 1000; // 2 минут в миллисекундах
    public static final int STOCH_RSI_PERIOD = 14;       // Период для RSI в Stoch RSI
    public static final int STOCH_RSI_K_SMOOTHING = 3;   // Сглаживание %K
    public static final int STOCH_RSI_D_SMOOTHING = 3;   // Сглаживание %D
}