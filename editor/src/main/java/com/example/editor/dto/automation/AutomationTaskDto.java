package com.example.editor.dto.automation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Задача в наборе. {@code id} — у уже сохранённой; без него или с чужим id задача создаётся.
 * Обёртки вместо примитивов: отсутствующее поле должно ловиться проверкой, а не молча
 * становиться нулём. {@code timeout_ms} без значения — 100.
 */
public record AutomationTaskDto(
        Long id,
        String name,
        Boolean enabled,
        @JsonProperty("period_ms") Integer periodMs,
        @JsonProperty("timeout_ms") Integer timeoutMs,
        @JsonProperty("stale_after_ms") Integer staleAfterMs,
        @JsonProperty("run_on_stale") Boolean runOnStale,
        List<AutomationIoDto> inputs,
        List<AutomationIoDto> outputs,
        @JsonProperty("writes_variables") List<String> writesVariables,
        String script) {
}
