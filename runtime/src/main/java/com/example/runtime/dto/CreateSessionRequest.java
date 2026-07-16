package com.example.runtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Запрос на создание сессии мониторинга")
public class CreateSessionRequest {

    @Schema(description = "ID корневого компонента-проекта в editor", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long projectId;
}
