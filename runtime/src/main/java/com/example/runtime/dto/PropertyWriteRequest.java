package com.example.runtime.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Запись локальных свойств из инспектора объектов. Всегда массив, как у {@link TagWriteRequest}. */
@Data
public class PropertyWriteRequest {

    @NotEmpty
    @Valid
    private List<PropertyWriteItem> writes;
}
