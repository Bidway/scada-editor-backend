package com.example.scriptcore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Словарь и приведение значений таблиц данных проекта. Общий для {@code editor} (проверка при
 * сохранении) и для читателей снимка ({@code runtime}, {@code automation}): значение, прошедшее
 * проверку, скрипт гарантированно прочитает тем же типом.
 */
public final class ProjectDataValues {

    public static final Set<String> VALUE_TYPES = Set.of("bool", "int", "float", "string", "json");

    /** Имя таблицы и колонки попадает в код скриптов — только латиница, цифры и {@code _}. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Поле строки с её ключом: колонка с таким именем его перекрыла бы. */
    public static final String KEY = "key";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProjectDataValues() {
    }

    /** {@code Set.of(...).contains(null)} бросает NPE — отсюда явная проверка. */
    public static boolean isValueType(String valueType) {
        return valueType != null && VALUE_TYPES.contains(valueType);
    }

    /**
     * Значение ячейки из JSON: {@code Boolean}, {@code Long}, {@code Double}, {@code String}, для
     * {@code json} — {@code Map}/{@code List}/примитив. Отсутствие и {@code null} — {@code null}.
     *
     * @throws IllegalArgumentException значение не соответствует типу колонки
     */
    public static Object convert(JsonNode value, String valueType) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return switch (valueType) {
            case "bool" -> {
                if (!value.isBoolean()) {
                    throw mismatch(value.toString(), valueType);
                }
                yield value.booleanValue();
            }
            case "int" -> {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                    throw mismatch(value.toString(), valueType);
                }
                yield value.longValue();
            }
            case "float" -> {
                if (!value.isNumber()) {
                    throw mismatch(value.toString(), valueType);
                }
                yield value.doubleValue();
            }
            case "string" -> {
                if (!value.isTextual()) {
                    throw mismatch(value.toString(), valueType);
                }
                yield value.textValue();
            }
            case "json" -> MAPPER.convertValue(value, Object.class);
            default -> throw new IllegalArgumentException("неизвестный тип '" + valueType + "'");
        };
    }

    /** {@code default_value} хранится строкой, как у переменных автоматизации. */
    public static Object convertDefault(String text, String valueType) {
        if (text == null) {
            return null;
        }
        try {
            return switch (valueType) {
                case "string" -> text;
                case "bool" -> {
                    if (!"true".equals(text) && !"false".equals(text)) {
                        throw mismatch(text, valueType);
                    }
                    yield Boolean.parseBoolean(text);
                }
                case "int" -> Long.parseLong(text.trim());
                case "float" -> Double.parseDouble(text.trim());
                case "json" -> MAPPER.convertValue(MAPPER.readTree(text), Object.class);
                default -> throw new IllegalArgumentException("неизвестный тип '" + valueType + "'");
            };
        } catch (NumberFormatException | JsonProcessingException e) {
            throw mismatch(text, valueType);
        }
    }

    private static IllegalArgumentException mismatch(String value, String valueType) {
        return new IllegalArgumentException("значение " + value + " не подходит к типу " + valueType);
    }
}
