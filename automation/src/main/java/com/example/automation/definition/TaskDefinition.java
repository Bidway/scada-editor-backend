package com.example.automation.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskDefinition(
        Long id,
        String name,
        boolean enabled,
        @JsonProperty("period_ms") int periodMs,
        @JsonProperty("timeout_ms") int timeoutMs,
        @JsonProperty("stale_after_ms") int staleAfterMs,
        @JsonProperty("run_on_stale") boolean runOnStale,
        List<IoDefinition> inputs,
        List<IoDefinition> outputs,
        @JsonProperty("writes_variables") List<String> writesVariables,
        String script) {

    public List<IoDefinition> inputsOrEmpty() {
        return inputs == null ? List.of() : inputs;
    }

    public List<IoDefinition> outputsOrEmpty() {
        return outputs == null ? List.of() : outputs;
    }

    public List<String> writesVariablesOrEmpty() {
        return writesVariables == null ? List.of() : writesVariables;
    }
}
