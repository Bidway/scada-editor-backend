package com.example.runtime.dto;

/**
 * Исход записи одного свойства; порядок в ответе совпадает с порядком {@code writes}.
 *
 * @param status  {@link #OK}, {@link #UNKNOWN_PROPERTY}, {@link #TAG_PROPERTY} или {@link #INVALID_VALUE}
 * @param message расшифровка для оператора; у {@code OK} — {@code null}
 */
public record PropertyWriteResult(Long propertyId, boolean success, String status, String message) {

    public static final String OK = "OK";
    /** Свойства нет в prod-выпуске: монитор показывает старое дерево или id чужой. */
    public static final String UNKNOWN_PROPERTY = "UNKNOWN_PROPERTY";
    /** Свойство привязано к тегу: его значение — телеметрия, писать надо в ПЛК через tags/write. */
    public static final String TAG_PROPERTY = "TAG_PROPERTY";
    /** Значение не приводится к {@code value_type} свойства. */
    public static final String INVALID_VALUE = "INVALID_VALUE";
}
