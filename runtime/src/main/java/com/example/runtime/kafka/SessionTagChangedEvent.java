package com.example.runtime.kafka;

/**
 * Публикуется {@link TagValueRouter} при диспетчинге обновления тега в сессию.
 * {@code ProcedureExecutionService} слушает его, чтобы пересчитать условие текущего
 * шага активной процедуры без прямой зависимости от {@code TagValueRouter} —
 * тот же приём, что {@link KafkaTagMessageEvent}.
 */
public record SessionTagChangedEvent(String sessionId) {
}
