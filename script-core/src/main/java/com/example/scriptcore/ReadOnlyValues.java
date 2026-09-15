package com.example.scriptcore;

import java.util.List;
import java.util.Map;

/** Общее для обёрток данных проекта: как оборачивать вложенные значения и чем отвечать на запись. */
final class ReadOnlyValues {

    private ReadOnlyValues() {
    }

    /** Вложенные коллекции колонки {@code json}: неизвестное поле — undefined, как у обычного объекта. */
    @SuppressWarnings("unchecked")
    static Object wrap(Object value) {
        if (value instanceof List<?> list) {
            return new ReadOnlyListProxy(list, ReadOnlyValues::wrap);
        }
        if (value instanceof Map<?, ?> map) {
            return new ReadOnlyMapProxy((Map<String, ?>) map, null);
        }
        return value;
    }

    static IllegalStateException readOnly() {
        return new IllegalStateException("data(): данные проекта только для чтения");
    }
}
