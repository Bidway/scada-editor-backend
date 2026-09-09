package com.example.runtime.script;

/** Чтение живого значения тега по короткому пути из {@code condition_script} шага процедуры. */
@FunctionalInterface
public interface TagReader {

    /** @return текущее значение тега (Boolean/Double/String), либо {@code null} — данных нет */
    Object read(String projectRelativePath);
}
