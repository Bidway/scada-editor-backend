package com.example.runtime.script;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Обёртки коллекций для одного запуска скрипта: по одной на исходный {@link List}/{@link Map},
 * с привязкой по identity.
 * <p>
 * Кеш здесь не оптимизация, а условие правильности. Пока обёртка строилась на каждое
 * обращение, она оборачивала свежую копию, и запись вида {@code props.arr[0] = 1} уходила в
 * копию, которую никто больше не видел (scada-4yy). Заодно кеш чинит две мелочи: в JS
 * {@code props.arr === props.arr} снова истинно, а цикл {@code for (i...) props.arr[i]}
 * перестаёт быть квадратичным.
 * <p>
 * Синхронизации нет и не нужно: {@code MapProxyObject} создаётся на каждый запуск скрипта
 * (см. {@code ScriptEngineService#execute}), а запуск идёт в одном потоке на одном контексте
 * из пула.
 */
final class ProxyWrappers {

    private final Map<Object, Object> byIdentity = new IdentityHashMap<>();

    /** Коллекции оборачиваем, всё остальное JS понимает и так. */
    @SuppressWarnings("unchecked")
    Object wrap(Object value) {
        if (value instanceof List<?>) {
            return byIdentity.computeIfAbsent(value,
                    v -> new ListProxyArray((List<Object>) v, this));
        }
        if (value instanceof Map<?, ?>) {
            // Ключи считаем строковыми: вложенные карты приходят либо из GraalValues.toJava,
            // либо из JSON — и там, и там ключ строка. Копировать карту ради приведения ключей
            // нельзя, иначе запись в поле снова потеряется.
            return byIdentity.computeIfAbsent(value,
                    v -> new MapProxyObject((Map<String, Object>) v, this));
        }
        return value;
    }

    /**
     * Значение вытеснено из коллекции: его обёртка больше не адресуема из скрипта, и держать
     * её незачем — иначе кеш растёт на каждую перезапись за весь запуск.
     */
    void forget(Object value) {
        if (value != null) {
            byIdentity.remove(value);
        }
    }
}
