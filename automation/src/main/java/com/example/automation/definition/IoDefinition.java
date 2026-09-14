package com.example.automation.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IoDefinition(String alias, String tag, @JsonProperty("value_type") String valueType) {
}
