package com.example.runtime.controller;

import com.example.runtime.dto.ProcedureConfirmRequest;
import com.example.runtime.dto.ProcedureJumpRequest;
import com.example.runtime.dto.ProcedureProjectRequest;
import com.example.runtime.dto.ProcedureStatusDto;
import com.example.runtime.recipe.ProcedureExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Выполнение процедурных рецептов (шагов) в мониторинге.
 * <p>
 * Процедура адресуется проектом, а не сессией: мойка принадлежит объекту, а не открытому
 * экрану, и обязана идти, когда оператор закрыл браузер. {@code sessionId} остаётся
 * необязательным и нужен ровно для одного — сказать, из какого экрана нажали, чтобы это
 * попало в лог и в событие остальным наблюдателям. Прав на процедуру он не даёт: подтвердить
 * или прервать может любой оператор, как на пульте с двумя кнопками.
 * <p>
 * Эндпоинта {@code resume-guess} больше нет: он существовал, пока состояние процедуры жило
 * только в памяти и терялось при перезапуске. Теперь оно в схеме {@code runtime} и
 * восстанавливается точно — держать рядом точный механизм и гадалку значило бы обречь
 * кого-то однажды поверить гадалке.
 */
@RestController
@RequestMapping("/api/runtime/recipes")
@RequiredArgsConstructor
@Tag(name = "Recipes", description = "Выполнение процедурных рецептов (шагов) в ПЛК")
public class ProcedureController {

    private final ProcedureExecutionService service;

    @Operation(summary = "Начать процедуру: выполнить шаг 1 и продвинуться по тривиальным условиям")
    @PostMapping("/{id}/start")
    public ProcedureStatusDto start(@PathVariable String id,
                                    @Valid @RequestBody ProcedureProjectRequest request,
                                    @RequestHeader(value = "X-Username", required = false) String username) {
        return service.start(request.getProjectId(), id, request.getSessionId(), username);
    }

    @Operation(summary = "Текущий статус выполняющейся процедуры")
    @GetMapping("/{id}/status")
    public ProcedureStatusDto status(@PathVariable String id, @RequestParam Long projectId) {
        return service.status(projectId, id);
    }

    @Operation(summary = "Ручное подтверждение текущего шага")
    @PostMapping("/{id}/confirm")
    public ProcedureStatusDto confirm(@PathVariable String id,
                                      @Valid @RequestBody ProcedureConfirmRequest request,
                                      @RequestHeader(value = "X-Username", required = false) String username) {
        return service.confirm(request.getProjectId(), id, request.getStepIndex(),
                request.getSessionId(), username);
    }

    @Operation(summary = "Пауза: процедура стоит на шаге, оборудование — в pause_action рецепта")
    @PostMapping("/{id}/pause")
    public ProcedureStatusDto pause(@PathVariable String id, @Valid @RequestBody ProcedureProjectRequest request,
                                    @RequestHeader(value = "X-Username", required = false) String username) {
        return service.pause(request.getProjectId(), id, request.getSessionId(), username);
    }

    @Operation(summary = "Продолжить после паузы; 409, если авария ещё активна")
    @PostMapping("/{id}/resume")
    public ProcedureStatusDto resume(@PathVariable String id, @Valid @RequestBody ProcedureProjectRequest request,
                                     @RequestHeader(value = "X-Username", required = false) String username) {
        return service.resume(request.getProjectId(), id, request.getSessionId(), username);
    }

    @Operation(summary = "Ручной выбор/восстановление шага")
    @PostMapping("/{id}/jump")
    public ProcedureStatusDto jump(@PathVariable String id,
                                   @Valid @RequestBody ProcedureJumpRequest request,
                                   @RequestHeader(value = "X-Username", required = false) String username) {
        return service.jump(request.getProjectId(), id, request.getStepIndex(),
                request.getSessionId(), username);
    }

    @Operation(summary = "Прервать выполнение процедуры")
    @PostMapping("/{id}/abort")
    public void abort(@PathVariable String id, @Valid @RequestBody ProcedureProjectRequest request,
                      @RequestHeader(value = "X-Username", required = false) String username) {
        service.abort(request.getProjectId(), id, request.getSessionId(), username);
    }
}
