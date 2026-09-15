package com.example.editor.service.data;

import com.example.editor.dto.data.ProjectDataColumnDto;
import com.example.editor.dto.data.ProjectDataRowDto;
import com.example.editor.dto.data.ProjectDataTableDto;
import com.example.editor.exception.ProjectDataValidationError;
import com.example.editor.exception.ProjectDataValidationException;
import com.example.scriptcore.ProjectDataValues;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Проверка набора таблиц проекта перед сохранением. Собирает все нарушения, а не падает на
 * первом. Приведение значений — то же, что у читателей снимка ({@link ProjectDataValues}):
 * прошедшее проверку значение скрипт прочитает без ошибки.
 */
@Component
public class ProjectDataValidator {

    /** Набор целиком лежит в памяти каждой сессии runtime и каждого проекта automation. */
    public static final int MAX_SET_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper;

    public ProjectDataValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(List<ProjectDataTableDto> tables) {
        List<ProjectDataValidationError> errors = new ArrayList<>();
        Set<String> tableNames = new HashSet<>();
        for (ProjectDataTableDto table : tables) {
            String name = table.name();
            if (name == null || !ProjectDataValues.NAME.matcher(name).matches()) {
                errors.add(error(name, "name", "Имя таблицы '" + name + "' недопустимо: латинские буквы, цифры и _"));
            } else if (!tableNames.add(name)) {
                errors.add(error(name, "name", "Таблица '" + name + "' объявлена дважды"));
            }
            Map<String, ProjectDataColumnDto> columns = checkColumns(errors, name, table.columns());
            checkRows(errors, name, columns, table.rows());
        }
        if (sizeOf(tables) > MAX_SET_BYTES) {
            errors.add(error(null, "tables", "Данные проекта больше " + MAX_SET_BYTES + " байт"));
        }
        if (!errors.isEmpty()) {
            throw new ProjectDataValidationException(errors);
        }
    }

    private Map<String, ProjectDataColumnDto> checkColumns(List<ProjectDataValidationError> errors, String table,
                                                           List<ProjectDataColumnDto> columns) {
        Map<String, ProjectDataColumnDto> byName = new LinkedHashMap<>();
        for (ProjectDataColumnDto column : columns) {
            String name = column.name();
            if (name == null || !ProjectDataValues.NAME.matcher(name).matches()) {
                errors.add(error(table, "columns.name", "Имя колонки '" + name + "' недопустимо: латинские буквы, цифры и _"));
                continue;
            }
            if (ProjectDataValues.KEY.equals(name)) {
                errors.add(error(table, "columns.name", "Колонку нельзя назвать '" + ProjectDataValues.KEY + "': это ключ строки"));
                continue;
            }
            if (byName.putIfAbsent(name, column) != null) {
                errors.add(error(table, "columns.name", "Колонка '" + name + "' объявлена дважды"));
                continue;
            }
            if (!ProjectDataValues.isValueType(column.valueType())) {
                errors.add(error(table, "columns.value_type",
                        "Колонка '" + name + "': value_type должен быть одним из " + ProjectDataValues.VALUE_TYPES));
                continue;
            }
            try {
                ProjectDataValues.convertDefault(column.defaultValue(), column.valueType());
            } catch (IllegalArgumentException e) {
                errors.add(error(table, "columns.default_value", "Колонка '" + name + "': " + e.getMessage()));
            }
        }
        return byName;
    }

    private void checkRows(List<ProjectDataValidationError> errors, String table,
                           Map<String, ProjectDataColumnDto> columns, List<ProjectDataRowDto> rows) {
        Set<String> keys = new HashSet<>();
        for (ProjectDataRowDto row : rows) {
            String key = row.key();
            if (key == null || key.isBlank()) {
                errors.add(error(table, "rows.key", "У строки нет ключа"));
            } else if (!keys.add(key)) {
                errors.add(error(table, "rows.key", "Ключ '" + key + "' повторяется"));
            }
            for (String column : row.values().keySet()) {
                if (!columns.containsKey(column)) {
                    errors.add(error(table, "rows.values", "Строка '" + key + "': неизвестная колонка '" + column + "'"));
                }
            }
            for (ProjectDataColumnDto column : columns.values()) {
                if (!ProjectDataValues.isValueType(column.valueType())) {
                    continue;
                }
                JsonNode value = row.values().get(column.name());
                Object converted;
                try {
                    converted = ProjectDataValues.convert(value, column.valueType());
                } catch (IllegalArgumentException e) {
                    errors.add(error(table, "rows.values",
                            "Строка '" + key + "', колонка '" + column.name() + "': " + e.getMessage()));
                    continue;
                }
                if (converted == null && Boolean.TRUE.equals(column.required()) && column.defaultValue() == null) {
                    errors.add(error(table, "rows.values",
                            "Строка '" + key + "': не заполнена обязательная колонка '" + column.name() + "'"));
                }
            }
        }
    }

    private int sizeOf(List<ProjectDataTableDto> tables) {
        try {
            return objectMapper.writeValueAsBytes(tables).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать данные проекта", e);
        }
    }

    private static ProjectDataValidationError error(String table, String field, String message) {
        return new ProjectDataValidationError(table, field, message);
    }
}
