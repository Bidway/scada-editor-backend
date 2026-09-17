package com.example.runtime.automation.engine;

/**
 * @param lastLagMs насколько такт начался позже расписания; {@code 0} — вовремя или вызван без расписания
 */
public record TaskStatusUpdate(long projectId, long taskId, String name, TaskState state, Long lastRunAtMs,
                               Long lastDurationMs, String lastError, long errorCount, Long lastLagMs) {
}
