package com.example.runtime.automation.engine;

import com.example.scriptcore.ProjectData;

/** Откуда движок берёт таблицы данных проекта. Отдельный интерфейс — чтобы движок проверялся без HTTP. */
@FunctionalInterface
public interface ProjectDataFetcher {

    /** @throws RuntimeException editor недоступен или ответ не разобран */
    ProjectData fetch(long projectId);
}
