package com.example.runtime.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Запрос, требующий только идентификатор сессии мониторинга — для start/confirm/abort. */
@Data
public class ProcedureSessionRequest {
    @NotBlank
    private String sessionId;
}
