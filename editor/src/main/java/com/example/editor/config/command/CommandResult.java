package com.example.editor.config.command;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class CommandResult<T> {
    private final Long userId;
    private final String entityType;
    private final Long entityId;
    private final String commandType;
    private final JsonNode payload;
    private final JsonNode undoPayload;
    private final T result; // ← ВАЖНО

    public CommandResult(
            Long userId,
            String entityType,
            Long entityId,
            String commandType,
            JsonNode payload,
            JsonNode undoPayload,
            T result
    ) {
        this.userId = userId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.commandType = commandType;
        this.payload = payload;
        this.undoPayload = undoPayload;
        this.result = result;
    }

    public T getResult() {
        return result;
    }
}