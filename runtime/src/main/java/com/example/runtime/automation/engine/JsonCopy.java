package com.example.runtime.automation.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Глубокая копия JSON-подобного дерева: скрипт мутирует копию, а зафиксированная память остаётся целой. */
final class JsonCopy {

    private JsonCopy() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : (Map<String, Object>) copy(source);
    }

    @SuppressWarnings("unchecked")
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((key, item) -> result.put(key, copy(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(copy(item)));
            return result;
        }
        return value;
    }
}
