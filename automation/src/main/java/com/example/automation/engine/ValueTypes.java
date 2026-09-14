package com.example.automation.engine;

/** Приведение значений к словарю {@code bool | int | float | string}. */
public final class ValueTypes {

    private ValueTypes() {
    }

    /** Значение тега → то, что увидит скрипт во входе. */
    public static Object toInput(Object value, String type) {
        if (value == null) {
            return null;
        }
        return switch (type == null ? "" : type) {
            case "bool" -> value instanceof Boolean b ? b : value instanceof Number n ? n.doubleValue() != 0 : Boolean.valueOf(value.toString());
            case "int" -> value instanceof Number n ? (Object) Math.round(n.doubleValue()) : value;
            case "float" -> value instanceof Number n ? (Object) n.doubleValue() : value;
            case "string" -> value.toString();
            default -> value;
        };
    }

    /** Значение из скрипта → значение команды. Неподходящий тип — ошибка такта, а не угаданная запись. */
    public static Object toOutput(Object value, String type) {
        if (value == null) {
            throw new IllegalArgumentException("null нельзя записать в тег");
        }
        return switch (type == null ? "" : type) {
            case "bool" -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                throw new IllegalArgumentException("ожидается bool, получено " + value);
            }
            case "int" -> {
                if (value instanceof Number n && Double.isFinite(n.doubleValue())) {
                    yield Math.round(n.doubleValue());
                }
                throw new IllegalArgumentException("ожидается int, получено " + value);
            }
            case "float" -> {
                if (value instanceof Number n && Double.isFinite(n.doubleValue())) {
                    yield n.doubleValue();
                }
                throw new IllegalArgumentException("ожидается float, получено " + value);
            }
            case "string" -> value.toString();
            default -> throw new IllegalArgumentException("неизвестный value_type " + type);
        };
    }

    /** {@code default_value} переменной (строка из editor) → типизированное значение. */
    public static Object fromDefault(String raw, String type) {
        if (raw == null) {
            return null;
        }
        try {
            return switch (type == null ? "" : type) {
                case "bool" -> Boolean.valueOf(raw.trim());
                case "int" -> Long.parseLong(raw.trim());
                case "float" -> Double.parseDouble(raw.trim());
                default -> raw;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
