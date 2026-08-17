package com.example.channel.command;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.UUID;

@Data
public class CommandResult<T> {
    private final String userName;
    private final String entityType;
    private final Long entityId;
    private final String commandType;
    private final UUID batchId;
    private final Integer sequence;
    private final JsonNode payload;
    private final JsonNode undoPayload;
    private final T result;

    public CommandResult(
            String userName, String entityType, Long entityId, String commandType,
            JsonNode payload, JsonNode undoPayload, T result
    ) {
        this(userName, entityType, entityId, commandType, null, null, payload, undoPayload, result);
    }

    public CommandResult(
            String userName, String entityType, Long entityId, String commandType,
            UUID batchId, Integer sequence,
            JsonNode payload, JsonNode undoPayload, T result
    ) {
        this.userName = userName;
        this.entityType = entityType;
        this.entityId = entityId;
        this.commandType = commandType;
        this.batchId = batchId;
        this.sequence = sequence;
        this.payload = payload;
        this.undoPayload = undoPayload;
        this.result = result;
    }
}
