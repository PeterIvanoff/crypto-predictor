package com.crypto;

public class TestMain {
    public static void main(String[] args) {
        DatabaseManager dbManager = new DatabaseManager();
        BybitClient client = new BybitClient(dbManager);

        // Держим приложение запущенным
        try {
            Thread.sleep(60000); // Ждем 1 минуту
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}