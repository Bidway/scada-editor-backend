package com.example.runtime.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Одна запись в ПЛК: путь тега (== {@code tag_id} свойства) и значение как ввёл оператор. */
@Data
public class TagWriteItem {

    @NotBlank
    private String tagId;

    /**
     * Значение как строка — типизацию делает бэкенд по {@link #valueType}. Пустая строка
     * допустима (строковый тег), поэтому валидация — {@code @NotNull}, а не {@code @NotBlank}.
     */
    @NotNull
    private String value;

    /** {@code value_type} свойства ("boolean"/"integer"/"float"/…); без него значение уходит строкой. */
    private String valueType;
}
