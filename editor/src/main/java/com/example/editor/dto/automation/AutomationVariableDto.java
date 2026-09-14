package com.example.editor.dto.automation;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AutomationVariableDto(
        String name,
        @JsonProperty("value_type") String valueType,
        @JsonProperty("default_value") String defaultValue,
        String description) {
}
