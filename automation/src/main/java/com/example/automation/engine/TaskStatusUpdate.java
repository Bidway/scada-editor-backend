package com.example.automation.engine;

public record TaskStatusUpdate(long projectId, long taskId, String name, TaskState state, Long lastRunAtMs,
                               Long lastDurationMs, String lastError, long errorCount) {
}
