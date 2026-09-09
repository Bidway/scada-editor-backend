package com.example.runtime.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Ручной выбор/восстановление текущего шага процедуры оператором. */
@Data
public class ProcedureJumpRequest {
    @NotBlank
    private String sessionId;
    /** Индекс шага (с нуля), на который нужно перейти. */
    @NotNull
    @Min(0)
    private Integer stepIndex;
}
