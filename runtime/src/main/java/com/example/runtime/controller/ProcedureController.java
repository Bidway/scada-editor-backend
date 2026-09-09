package com.example.runtime.controller;

import com.example.runtime.dto.ProcedureJumpRequest;
import com.example.runtime.dto.ProcedureResumeGuessDto;
import com.example.runtime.dto.ProcedureSessionRequest;
import com.example.runtime.dto.ProcedureStatusDto;
import com.example.runtime.recipe.ProcedureExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Выполнение процедурных рецептов (шагов) в мониторинге. Значения набора
 * server-authoritative: оператор передаёт только id рецепта и sessionId.
 */
@RestController
@RequestMapping("/api/runtime/recipes")
@RequiredArgsConstructor
@Tag(name = "Recipes", description = "Выполнение процедурных рецептов (шагов) в ПЛК")
public class ProcedureController {

    private final ProcedureExecutionService service;

    @Operation(summary = "Начать процедуру: выполнить шаг 1 и продвинуться по тривиальным условиям")
    @PostMapping("/{id}/start")
    public ProcedureStatusDto start(@PathVariable String id, @Valid @RequestBody ProcedureSessionRequest request) {
        return service.start(request.getSessionId(), id);
    }

    @Operation(summary = "Текущий статус выполняющейся процедуры")
    @GetMapping("/{id}/status")
    public ProcedureStatusDto status(@PathVariable String id, @RequestParam String sessionId) {
        return service.status(sessionId, id);
    }

    @Operation(summary = "Ручное подтверждение текущего шага")
    @PostMapping("/{id}/confirm")
    public ProcedureStatusDto confirm(@PathVariable String id, @Valid @RequestBody ProcedureSessionRequest request) {
        return service.confirm(request.getSessionId(), id);
    }

    @Operation(summary = "Ручной выбор/восстановление шага")
    @PostMapping("/{id}/jump")
    public ProcedureStatusDto jump(@PathVariable String id, @Valid @RequestBody ProcedureJumpRequest request) {
        return service.jump(request.getSessionId(), id, request.getStepIndex());
    }

    @Operation(summary = "Прервать выполнение процедуры")
    @PostMapping("/{id}/abort")
    public void abort(@PathVariable String id, @Valid @RequestBody ProcedureSessionRequest request) {
        service.abort(request.getSessionId(), id);
    }

    @Operation(summary = "Подсказка вероятного текущего шага после сбоя runtime — ничего не меняет")
    @GetMapping("/{id}/resume-guess")
    public ProcedureResumeGuessDto resumeGuess(@PathVariable String id, @RequestParam String sessionId) {
        return new ProcedureResumeGuessDto(service.resumeGuess(sessionId, id));
    }
}
