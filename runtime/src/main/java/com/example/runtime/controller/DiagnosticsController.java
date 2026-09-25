package com.example.runtime.controller;

import com.example.runtime.script.ScriptFailureRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Диагностика экземпляра runtime. Отказы хранятся в памяти каждого экземпляра, поэтому запрос не
 * пересылается владельцу проекта ({@code OwnerForwardingFilter}): отвечает тот, кого спросили.
 */
@RestController
@RequestMapping("/api/runtime/diagnostics")
@RequiredArgsConstructor
@Tag(name = "Diagnostics", description = "Отказы скриптов экземпляра runtime")
public class DiagnosticsController {

    private final ScriptFailureRegistry failures;

    @Operation(summary = "Последние отказы скриптов этого экземпляра, свежие первыми (до 200)")
    @GetMapping("/script-failures")
    public List<ScriptFailureRegistry.Failure> scriptFailures(
            @RequestParam(name = "projectId", required = false) Long projectId) {
        return failures.recent().stream()
                .filter(f -> projectId == null || Objects.equals(projectId, f.projectId()))
                .toList();
    }
}
