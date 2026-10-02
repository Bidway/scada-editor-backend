package com.example.runtime.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Аргументы ACTION ({@code args} входящего сообщения) — выбор оператора, который скрипт действия
 * видит объектом {@code args}. На провод и в движок они идут JSON-строкой: её же берёт ключ
 * дедупликации, чтобы «рецепт 4», а следом «рецепт 5» не считались дребезгом одного клика.
 */
public final class ActionArgs {

    /** Аргументы — это выбор из меню, а не данные: больше 4 КБ — ошибка фронта, а не нужда. */
    public static final int MAX_JSON_CHARS = 4096;

    public static final String EMPTY = "{}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ActionArgs() {
    }

    /**
     * JSON-строка аргументов; нет аргументов — {@link #EMPTY}.
     *
     * @throws IllegalArgumentException если аргументы длиннее {@link #MAX_JSON_CHARS}
     */
    public static String toJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return EMPTY;
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("аргументы действия не сериализуются: " + e.getMessage(), e);
        }
        if (json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("аргументы действия длиннее " + MAX_JSON_CHARS + " символов");
        }
        return json;
    }
}
