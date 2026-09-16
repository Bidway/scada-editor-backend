package com.example.runtime.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Адресация процедуры: проект обязателен, он же ключ. {@code sessionId} необязателен и служит
 * только подписью «из какого экрана нажали» — прав на процедуру он не даёт.
 */
@Data
public class ProcedureProjectRequest {
    @NotNull
    private Long projectId;
    private String sessionId;
}
