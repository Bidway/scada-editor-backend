package com.example.runtime.automation.engine;

import java.util.Map;

/** Куда уходят результаты тактов: статус, память задачи, переменные. Реализация — StateSink. */
public interface TaskObserver {

    void status(TaskStatusUpdate update);

    void checkpoint(long projectId, long taskId, String definitionHash, Map<String, Object> state);

    void variable(long projectId, String name, Object value);

    /** Синхронно сбросить в базу накопленное по проекту — перед остановкой его задач. */
    default void flushProject(long projectId) {
    }
}
