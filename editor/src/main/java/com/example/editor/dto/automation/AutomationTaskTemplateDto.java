package com.example.editor.dto.automation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Шаблон задачи на проводе. Поля повторяют {@link AutomationTaskDto}, кроме {@code enabled} и
 * {@code project_id}: их у шаблона нет, а вместо {@code tag} у входов и выходов — {@code example_tag}.
 * {@code timeout_ms} без значения — 100, как у задачи.
 */
public record AutomationTaskTemplateDto(
        Long id,
        String name,
        String category,
        String description,
        @JsonProperty("period_ms") Integer periodMs,
        @JsonProperty("timeout_ms") Integer timeoutMs,
        @JsonProperty("stale_after_ms") Integer staleAfterMs,
        @JsonProperty("run_on_stale") Boolean runOnStale,
        List<AutomationTemplateIoDto> inputs,
        List<AutomationTemplateIoDto> outputs,
        @JsonProperty("writes_variables") List<String> writesVariables,
        String script) {
}
