package com.example.scriptcore;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.util.Map;

/**
 * Функция {@code data()} серверных скриптов: {@code data('t')} — все строки таблицы,
 * {@code data('t', 'k')} — строка по ключу или {@code null}. Одна на все движки: задачи
 * {@code automation}, скрипты и условия шагов {@code runtime}.
 */
public final class DataFunction implements ProxyExecutable {

    private final ProjectData data;

    /** @param data снимок; {@code null} — данные ещё не загружены (automation при недоступном editor) */
    public DataFunction(ProjectData data) {
        this.data = data;
    }

    @Override
    public Object execute(Value... arguments) {
        if (data == null) {
            throw new IllegalStateException("data(): данные проекта не загружены");
        }
        if (arguments.length < 1 || arguments.length > 2 || !arguments[0].isString()
                || (arguments.length == 2 && !arguments[1].isString())) {
            throw new IllegalArgumentException("data(): ожидается (таблица) или (таблица, ключ)");
        }
        String tableName = arguments[0].asString();
        ProjectData.Table table = data.table(tableName);
        if (table == null) {
            throw new IllegalArgumentException("data(): неизвестная таблица '" + tableName + "'");
        }
        if (arguments.length == 1) {
            return new ReadOnlyListProxy(table.rows(), row -> row(table, row));
        }
        Map<String, Object> row = table.rowsByKey().get(arguments[1].asString());
        return row == null ? null : row(table, row);
    }

    @SuppressWarnings("unchecked")
    private static Object row(ProjectData.Table table, Object row) {
        return new ReadOnlyMapProxy((Map<String, ?>) row, table.name());
    }
}
