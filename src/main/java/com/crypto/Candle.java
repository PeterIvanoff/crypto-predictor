package com.crypto;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class Candle {
    private final long timestamp;
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final double volume;

    public Candle(long timestamp, double open, double high, double low, double close, double volume) {
        this.timestamp = timestamp;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public double getVolume() {
        return volume;
    }
    public String getFormattedTimestamp() {
        Date date = new Date(timestamp);
        SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("GMT+2")); // Можешь заменить на "Europe/Moscow" или свою зону
        return format.format(date);
    }
}