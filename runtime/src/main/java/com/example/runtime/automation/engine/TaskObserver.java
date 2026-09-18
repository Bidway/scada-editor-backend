package com.example.runtime.automation.engine;

import java.util.Map;
import java.util.Set;

/** Куда уходят результаты тактов: статус, память задачи, переменные. Реализация — StateSink. */
public interface TaskObserver {

    void status(TaskStatusUpdate update);

    void checkpoint(long projectId, long taskId, String definitionHash, Map<String, Object> state);

    void variable(long projectId, String name, Object value);

    /** Синхронно сбросить в базу накопленное по проекту — перед остановкой его задач. */
    default void flushProject(long projectId) {
    }

    /**
     * Забыть задачи и переменные проекта, которых нет в новом наборе определений: статус, память и
     * значение удалённой задачи иначе оставались навсегда и показывались в мониторе (scada-e17).
     * Пустые множества — забыть всё по проекту.
     */
    default void retain(long projectId, Set<Long> taskIds, Set<String> variableNames) {
    }
}
