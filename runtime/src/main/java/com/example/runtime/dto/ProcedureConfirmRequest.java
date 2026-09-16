package com.example.runtime.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Подтверждение текущего шага. {@code stepIndex} необязателен: если фронт его прислал, это шаг,
 * который оператор видел на экране, и несовпадение с текущим даёт 409 вместо подтверждения не
 * того шага. Не прислал — подтверждается текущий.
 */
@Data
public class ProcedureConfirmRequest {
    @NotNull
    private Long projectId;
    private String sessionId;
    @Min(0)
    private Integer stepIndex;
}
