package com.example.runtime.controller;

import com.example.runtime.dto.CreateSessionRequest;
import com.example.runtime.dto.SessionResponse;
import com.example.runtime.dto.TagSnapshot;
import com.example.runtime.session.RuntimeSessionService;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Только создание/остановка сессии мониторинга. Никаких write-эндпоинтов для
 * модели проекта — runtime read-only, редактирование остаётся в editor.
 */
@RestController
@RequestMapping("/api/runtime/sessions")
@RequiredArgsConstructor
@Tag(name = "Sessions", description = "Сессии мониторинга SCADA-проекта")
public class RuntimeController {

    private final RuntimeSessionService sessionService;


    @ApiResponse(responseCode = "200", description = "Сессия создана")
    @ApiResponse(responseCode = "400", description = "Проект не найден или неверный запрос")
    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
        RuntimeSessionService.SessionBootstrap bootstrap = sessionService.createSession(request.getProjectId());
        SessionResponse response = new SessionResponse(
                bootstrap.session().getId(),
                "/ws/runtime/" + bootstrap.session().getId(),
                bootstrap.projectTree());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Снимок текущих значений тегов строк таблицы (перед загрузкой рецепта)")
    @GetMapping("/{sessionId}/snapshot")
    public ResponseEntity<List<TagSnapshot>> snapshot(
            @Parameter(description = "Идентификатор сессии") @PathVariable String sessionId,
            @Parameter(description = "Идентификатор компонента-таблицы") @RequestParam Long componentId) {
        return ResponseEntity.ok(sessionService.snapshot(sessionId, componentId));
    }


    @ApiResponse(responseCode = "204", description = "Сессия остановлена")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> closeSession(
            @Parameter(description = "Идентификатор сессии") @PathVariable String id) {
        sessionService.closeSession(id);
        return ResponseEntity.noContent().build();
    }
}
