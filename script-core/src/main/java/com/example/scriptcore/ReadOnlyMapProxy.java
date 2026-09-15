package com.example.scriptcore;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.Map;
import java.util.Set;

/**
 * Объект данных проекта для JS: строка таблицы ({@code table != null}) или вложенный объект
 * колонки {@code json} ({@code table == null}).
 * <p>
 * У строки неизвестная колонка — ошибка: иначе опечатка {@code row.densty} молча давала бы
 * undefined, а регулятор считал бы по нему. Для этого {@link #hasMember} отвечает «есть» на любое
 * имя, кроме тех, что движок JS спрашивает у объекта сам ({@link #ENGINE_PROBES}) — на них
 * ошибка сломала бы {@code JSON.stringify(row)} и приведение к строке.
 */
final class ReadOnlyMapProxy implements ProxyObject {

    private static final Set<String> ENGINE_PROBES = Set.of(
            "toString", "valueOf", "toJSON", "toLocaleString", "constructor", "then");

    private final Map<String, ?> map;
    private final String table;

    ReadOnlyMapProxy(Map<String, ?> map, String table) {
        this.map = map;
        this.table = table;
    }

    @Override
    public Object getMember(String key) {
        if (map.containsKey(key)) {
            return ReadOnlyValues.wrap(map.get(key));
        }
        if (table == null || ENGINE_PROBES.contains(key)) {
            return null;
        }
        throw new IllegalArgumentException("data(): неизвестная колонка '" + key + "' в таблице '" + table + "'");
    }

    /**
     * {@link ProxyArray}, а не {@code String[]}: контекст собран с {@code HostAccess.NONE}, и
     * Java-массив ключей движок отвергает — {@code JSON.stringify(row)} падал бы.
     */
    @Override
    public Object getMemberKeys() {
        return ProxyArray.fromArray(map.keySet().toArray());
    }

    @Override
    public boolean hasMember(String key) {
        return map.containsKey(key) || (table != null && !ENGINE_PROBES.contains(key));
    }

    @Override
    public void putMember(String key, Value value) {
        throw ReadOnlyValues.readOnly();
    }

    @Override
    public boolean removeMember(String key) {
        throw ReadOnlyValues.readOnly();
    }
}
