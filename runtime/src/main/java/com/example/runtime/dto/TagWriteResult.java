package com.example.runtime.dto;

/**
 * Исход записи одной строки батча — порядок в ответе совпадает с порядком {@code writes}
 * в запросе, один результат на один элемент.
 *
 * @param tagId   тег, которого касается результат (тот же, что в запросе)
 * @param success подтверждено ли применение контроллером
 * @param status  код исхода — см. {@code CommandOutcome}, плюс локальный {@code INVALID_VALUE}
 * @param message человекочитаемая расшифровка — показывается оператору как есть
 */
public record TagWriteResult(String tagId, boolean success, String status, String message) {

    /** Значение не привелось к объявленному {@code valueType} — в ПЛК не отправлялось ничего. */
    public static final String INVALID_VALUE = "INVALID_VALUE";
}
