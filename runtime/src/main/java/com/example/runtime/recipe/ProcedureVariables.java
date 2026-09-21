package com.example.runtime.recipe;

/**
 * Переменные автоматизации проекта глазами процедуры. Через них рецепт взводит аварии
 * (действие шага с путём {@code var:<имя>}) и узнаёт об активной аварии (запись манифеста
 * {@code ALARM}). Интерфейс, а не прямая зависимость от {@code AutomationEngine}: движок процедур
 * не тянет за собой пакет автоматизации, а тесты подставляют карту.
 */
public interface ProcedureVariables {

    /** @return значение или {@code null}, если задачи проекта не исполняются или переменной нет */
    Object read(long projectId, String name);

    /** @return {@code false}, если переменная не объявлена, значение не подходит по типу или задачи проекта не исполняются */
    boolean write(long projectId, String name, Object value);
}
