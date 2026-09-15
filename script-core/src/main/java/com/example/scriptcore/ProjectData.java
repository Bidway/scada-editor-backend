package com.example.scriptcore;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Неизменяемый снимок таблиц данных проекта — то, что видит {@code data()}. Строится из ответа
 * {@code GET /api/editor/projects/{id}/data}: значения приведены к типам колонок, пустые заменены
 * {@code default_value}. Один снимок читают все такты и все скрипты сессии.
 */
public final class ProjectData {

    public static final ProjectData EMPTY = new ProjectData(Map.of());

    /** Строки — в порядке редактора и по ключу; каждая строка содержит поле {@code key}. */
    public record Table(String name, Set<String> columns, List<Map<String, Object>> rows,
                        Map<String, Map<String, Object>> rowsByKey) {
    }

    private final Map<String, Table> tables;

    private ProjectData(Map<String, Table> tables) {
        this.tables = tables;
    }

    /** @return таблица или {@code null}, если такой нет */
    public Table table(String name) {
        return tables.get(name);
    }

    public static ProjectData parse(JsonNode body) {
        JsonNode tablesNode = body == null ? null : body.get("tables");
        if (tablesNode == null || !tablesNode.isArray()) {
            return EMPTY;
        }
        Map<String, Table> tables = new HashMap<>();
        for (JsonNode tableNode : tablesNode) {
            String name = tableNode.path("name").asText();
            Map<String, String> types = new LinkedHashMap<>();
            Map<String, String> defaults = new HashMap<>();
            for (JsonNode column : tableNode.path("columns")) {
                String columnName = column.path("name").asText();
                types.put(columnName, column.path("value_type").asText());
                JsonNode defaultValue = column.get("default_value");
                defaults.put(columnName, defaultValue == null || defaultValue.isNull() ? null : defaultValue.asText());
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Map<String, Object>> rowsByKey = new HashMap<>();
            for (JsonNode rowNode : tableNode.path("rows")) {
                String key = rowNode.path("key").asText();
                JsonNode values = rowNode.path("values");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(ProjectDataValues.KEY, key);
                types.forEach((column, type) -> {
                    Object value = ProjectDataValues.convert(values.get(column), type);
                    row.put(column, value != null ? value : ProjectDataValues.convertDefault(defaults.get(column), type));
                });
                Map<String, Object> frozen = Collections.unmodifiableMap(row);
                rows.add(frozen);
                rowsByKey.put(key, frozen);
            }
            tables.put(name, new Table(name, Collections.unmodifiableSet(types.keySet()),
                    List.copyOf(rows), Map.copyOf(rowsByKey)));
        }
        return new ProjectData(Map.copyOf(tables));
    }
}
