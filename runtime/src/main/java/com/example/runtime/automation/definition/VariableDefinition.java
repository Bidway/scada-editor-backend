package com.example.runtime.automation.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VariableDefinition(
        String name,
        @JsonProperty("value_type") String valueType,
        @JsonProperty("default_value") String defaultValue,
        String description) {
}
