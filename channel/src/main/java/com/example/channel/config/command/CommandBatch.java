package com.example.channel.config.command;

import java.util.UUID;

/**
 * Groups related command log entries so they can be undone atomically via batchId.
 */
public class CommandBatch {

    private final UUID batchId;
    private int sequence;

    private CommandBatch(UUID batchId) {
        this.batchId = batchId;
    }

    public static CommandBatch start() {
        return new CommandBatch(UUID.randomUUID());
    }

    public UUID getBatchId() {
        return batchId;
    }

    public int nextSequence() {
        return sequence++;
    }
}
