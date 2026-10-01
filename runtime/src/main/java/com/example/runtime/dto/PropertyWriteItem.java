package com.example.runtime.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Одно значение локального свойства из инспектора объектов: id свойства и значение как ввёл оператор. */
@Data
public class PropertyWriteItem {

    @NotNull
    private Long propertyId;

    /** Строкой, как у записи тега: приводит бэкенд по {@code value_type} свойства из выпуска. */
    @NotNull
    private String value;
}
