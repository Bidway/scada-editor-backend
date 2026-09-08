package com.example.runtime.kafka;

/**
 * Приведение введённого оператором строкового значения к Java-типу перед отправкой в
 * {@link CommandProducer} — используется и применением наборов, и точечной записью тега.
 */
public final class ValueCoercion {

    private ValueCoercion() {
    }

    /** Непарсящееся под тип число отдаётся строкой — решит драйвер. */
    public static Object coerce(String value, String valueType) {
        if (value == null) {
            return null;
        }
        if (valueType == null) {
            return value;
        }
        String raw = value.trim();
        try {
            switch (valueType.trim().toLowerCase()) {
                case "number":
                case "double":
                case "float":
                case "real":
                    return Double.parseDouble(raw);
                case "int":
                case "integer":
                case "long":
                    return Long.parseLong(raw);
                case "bool":
                case "boolean":
                    return parseBoolean(raw);
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /**
     * Разбор булевой уставки по явному списку написаний. В отличие от {@link Boolean#valueOf},
     * не превращает всё неизвестное в {@code false}, а сигнализирует об ошибке: дискретный тег —
     * это клапан или пуск/стоп механизма, и молча отправленная противоположная уставка опаснее
     * отказа записи.
     *
     * @throws IllegalArgumentException если значение не опознано — писать в ПЛК нечего
     */
    public static boolean parseBoolean(String raw) {
        switch (raw.toLowerCase()) {
            case "true":
            case "1":
            case "on":
            case "yes":
            case "да":
                return true;
            case "false":
            case "0":
            case "off":
            case "no":
            case "нет":
                return false;
            default:
                throw new IllegalArgumentException(
                        "Значение '" + raw + "' не является булевым: ожидается true/false, 1/0, on/off, yes/no, да/нет");
        }
    }
}
