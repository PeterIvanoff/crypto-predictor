package com.crypto;

public class Constants {
    public static final int TRAINING_PERIOD = 2000;
    public static final int SMA_PERIOD = 14;
    public static final int RSI_PERIOD = 14;
    public static final int STOCHASTIC_K_PERIOD = 14;
    public static final int STOCHASTIC_K_SMOOTHING = 3;
    public static final int STOCHASTIC_D_SMOOTHING = 3;
    public static final int STOCH_RSI_PERIOD = 14;
    public static final int STOCH_RSI_K_SMOOTHING = 3;
    public static final int STOCH_RSI_D_SMOOTHING = 3;
    public static final String CURRENCY_PAIR = "ETHUSDT";
    public static final String TIMEFRAME = "5m";
    public static final String BYBIT_API_URL = "https://api.bybit.com";
    public static final String BYBIT_WS_URL = "wss://stream.bybit.com/v5/public/linear";

    // Выбор модели
    public static final String MODEL_TYPE = "LSTM"; // "MLP" или "LSTM"

    // Параметры LSTM
    public static final int LSTM_INPUT_SIZE = 11;    // open, high, low, close, volume, SMA, RSI, StochK, StochD, StochRSI_K, StochRSI_D
    public static final int LSTM_HIDDEN_SIZE = 50;
    public static final int LSTM_OUTPUT_SIZE = 1;
    public static final int LSTM_TIME_STEPS = 10;
    public static final int LSTM_EPOCHS = 50;
    public static final double LSTM_LEARNING_RATE = 0.001;
}