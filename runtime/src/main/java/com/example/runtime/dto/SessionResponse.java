package com.example.runtime.dto;

import com.example.runtime.client.dto.EditorComponentDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Ответ на старт сессии мониторинга. Дерево проекта отдаётся сразу целиком
 * (включая Binding.script — фронт сам интерпретирует его для отрисовки),
 * дальше живые обновления идут по wsPath.
 */
@Data
@Schema(description = "Созданная сессия мониторинга")
public class SessionResponse {

    @Schema(description = "UUID сессии", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private String sessionId;

    @Schema(description = "Путь WebSocket (подключаться напрямую к runtime:8085)", example = "/ws/runtime/3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private String wsPath;

    @Schema(description = "Полное дерево проекта из editor")
    private EditorComponentDto projectTree;

    public SessionResponse(String sessionId, String wsPath, EditorComponentDto projectTree) {
        this.sessionId = sessionId;
        this.wsPath = wsPath;
        this.projectTree = projectTree;
    }
}
