package com.example.runtime.automation.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Текущие значения переменных проекта. Общий на все задачи проекта. */
public final class VariableBoard {

    private final Map<String, Object> values;

    public VariableBoard(Map<String, Object> initial) {
        this.values = new HashMap<>(initial);
    }

    public synchronized Object get(String name) {
        return values.get(name);
    }

    public synchronized Map<String, Object> snapshot() {
        return new HashMap<>(values);
    }

    /** @return {@code true}, если значение действительно изменилось */
    public synchronized boolean set(String name, Object value) {
        boolean changed = !values.containsKey(name) || !Objects.equals(values.get(name), value);
        values.put(name, value);
        return changed;
    }
}
