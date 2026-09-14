package com.example.scriptcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Разобранное тело сообщения телеметрии {@code scada.tags}. Общий код {@code runtime} и
 * {@code automation}: оба обязаны одинаково понимать качество и время значения.
 * <p>
 * В топике сосуществуют три формата: текущий {@code {value, quality, timestamp}}, прежний
 * 13-польный {@code TelemetryMessage} (берётся {@code value}) и голый скаляр. Отсутствующее
 * {@code quality} — достоверно: иначе выкладка шлюза и потребителей была бы связана по порядку.
 *
 * @param value    значение тега сырой строкой; {@code null} — значения нет
 * @param good     достоверно ли значение — только {@code "GOOD"}
 * @param sourceTs момент снятия с контроллера, epoch ms; {@code null} — не прислан
 */
public record TelemetryEnvelope(String value, boolean good, Long sourceTs) {

    public static final String GOOD = "GOOD";

    /**
     * Строгий формат числа: отвергает то, что {@link Double#parseDouble} проглотил бы неверно
     * ({@code NaN}, {@code "1d"}, hex, статус-коды с ведущими нулями — {@code "0012"} остаётся строкой).
     */
    private static final Pattern NUMERIC = Pattern.compile("[+-]?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

    /** {@code 1e11} мс — 1973 год, {@code 1e11} с — 5138-й: порог надёжно различает единицы. */
    private static final double EPOCH_SECONDS_CEILING = 1e11;

    public static TelemetryEnvelope raw(String value) {
        return new TelemetryEnvelope(value, true, null);
    }

    /**
     * @param onMalformed вызывается, если тело похоже на JSON, но не разбирается; результат —
     *                    сырое тело как значение (так вели себя потребители и раньше)
     */
    public static TelemetryEnvelope parse(ObjectMapper mapper, String raw, Consumer<Exception> onMalformed) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '{') {
            return raw(raw);
        }
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode value = root.get("value");
            if (value == null) {
                return raw(raw);
            }
            return new TelemetryEnvelope(
                    value.isNull() ? null : value.asText(),
                    isGood(root.get("quality")),
                    sourceTs(root.get("timestamp")));
        } catch (Exception e) {
            onMalformed.accept(e);
            return raw(raw);
        }
    }

    /** Строка тега → примитив JS: {@code true/false} → Boolean, строгое число → Double, иначе строка. */
    public static Object coerce(String value) {
        if (value == null) {
            return null;
        }
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.valueOf(value);
        }
        if (NUMERIC.matcher(value).matches()) {
            double d = Double.parseDouble(value);
            if (Double.isFinite(d)) {
                return d;
            }
        }
        return value;
    }

    /** Достоверно только {@code GOOD}: у OPC UA есть ещё {@code UNCERTAIN}, это не факт. */
    private static boolean isGood(JsonNode quality) {
        return quality == null || quality.isNull() || GOOD.equalsIgnoreCase(quality.asText());
    }

    /** ISO-8601, дробные epoch-секунды (так пишет шлюз) или целые epoch-мс; неудача — {@code null}. */
    private static Long sourceTs(JsonNode timestamp) {
        if (timestamp == null || timestamp.isNull()) {
            return null;
        }
        if (timestamp.isNumber()) {
            double raw = timestamp.asDouble();
            return raw < EPOCH_SECONDS_CEILING ? Math.round(raw * 1000) : (long) raw;
        }
        try {
            return Instant.parse(timestamp.asText()).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }
}
