package com.example.runtime.script;

/**
 * Чтение локального свойства проекта из {@code condition_script} шага процедуры: строки без
 * тега вроде таблицы «Параметры станции». Свойство адресуется парой имён, потому что имя
 * свойства уникально только в пределах своего компонента.
 */
@FunctionalInterface
public interface PropertyReader {

    /**
     * @return текущее значение свойства в сессии (Boolean/Double/String), либо {@code null} —
     *         такого свойства нет или адрес неоднозначен (несколько одноимённых компонентов)
     */
    Object read(String componentName, String propertyName);
}
