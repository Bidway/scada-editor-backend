package com.example.editor.dto.automation;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AutomationWatchdogDto(
        String tag,
        @JsonProperty("period_ms") Integer periodMs) {
}
