package com.example.editor.dto.automation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Тело {@code PUT}: весь набор сразу и версия, от которой отталкивался клиент. */
public record AutomationSaveRequestDto(
        @JsonProperty("based_on_version") Integer basedOnVersion,
        List<AutomationTaskDto> tasks,
        List<AutomationVariableDto> variables,
        AutomationWatchdogDto watchdog) {
}
