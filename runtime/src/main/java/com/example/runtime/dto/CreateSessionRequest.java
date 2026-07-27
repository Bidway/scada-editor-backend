package com.example.runtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Запрос на создание сессии мониторинга")
public class CreateSessionRequest {

    @NotNull
    @Schema(description = "ID корневого компонента-проекта в editor", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long projectId;
}
