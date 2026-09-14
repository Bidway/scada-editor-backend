package com.example.automation.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Сообщение {@code automation.definitions}: набор проекта целиком, как его публикует editor. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectDefinitions(
        @JsonProperty("project_id") Long projectId,
        Integer version,
        List<TaskDefinition> tasks,
        List<VariableDefinition> variables,
        WatchdogDefinition watchdog) {

    public List<TaskDefinition> tasksOrEmpty() {
        return tasks == null ? List.of() : tasks;
    }

    public List<VariableDefinition> variablesOrEmpty() {
        return variables == null ? List.of() : variables;
    }
}
