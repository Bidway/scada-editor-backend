package com.example.scadaeditorbackend.command;

import com.fasterxml.jackson.databind.JsonNode;

public record CommandResult(
        Long userId,
        String entityType,
        Long entityId,
        String commandType,
        JsonNode payload,
        JsonNode undoPayload
) {}
