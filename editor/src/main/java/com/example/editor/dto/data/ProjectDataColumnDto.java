package com.example.editor.dto.data;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProjectDataColumnDto(
        String name,
        String title,
        @JsonProperty("value_type") String valueType,
        Boolean required,
        @JsonProperty("default_value") String defaultValue) {
}
