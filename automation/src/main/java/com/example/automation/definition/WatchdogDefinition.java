package com.example.automation.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WatchdogDefinition(String tag, @JsonProperty("period_ms") int periodMs) {
}
