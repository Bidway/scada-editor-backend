package com.example.automation.engine;

import java.util.Map;

/** Куда уходят результаты тактов: статус, память задачи, переменные. Реализация — StateSink. */
public interface TaskObserver {

    void status(TaskStatusUpdate update);

    void checkpoint(long projectId, long epoch, long taskId, String definitionHash, Map<String, Object> state);

    void variable(long projectId, long epoch, String name, Object value);

    /** Синхронно сбросить в базу накопленное по проекту — перед остановкой его задач. */
    default void flushProject(long projectId) {
    }
}
