package com.example.automation.engine;

/**
 * @param value        последнее достоверное значение, приведённое к Boolean/Double/String
 * @param good         достоверно ли оно сейчас
 * @param receivedAtMs момент приёма по часам сервиса — возраст считается от него, а не от часов ПЛК
 */
public record TagReading(Object value, boolean good, long receivedAtMs) {
}
