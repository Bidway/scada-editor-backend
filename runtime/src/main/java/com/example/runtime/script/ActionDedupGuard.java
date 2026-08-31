package com.example.runtime.script;

import com.example.runtime.config.RuntimeProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Отсекает повторный ACTION с тем же ключом раньше {@code minIntervalMs} — дребезг клика на
 * фронте и повторная отправка того же WS-сообщения иначе выполнили бы скрипт (и, значит,
 * возможную запись в ПЛК) дважды. Не знает про WS и про скрипты — ключ произвольный
 * ({@code Object}, с адекватными {@code equals}/{@code hashCode}), см. {@link #allow}. scada-au4.
 */
@Component
public class ActionDedupGuard {

    /** Защита от неограниченного роста на длинной сессии — тот же приём, что sourceCache в ScriptEngineService. */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final long minIntervalMs;
    private final Clock clock;

    /** LRU с потолком, синхронизирован — вызывается из потоков обработки WS-фреймов. */
    private final Map<Object, Long> lastAllowedAt = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, Long> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            });

    public ActionDedupGuard(RuntimeProperties properties) {
        this(properties.getScript().getActionMinIntervalMs(), Clock.systemUTC());
    }

    /** Для тестов на границы окна — фиксированный {@link Clock} вместо {@code Thread.sleep}. */
    ActionDedupGuard(long minIntervalMs, Clock clock) {
        this.minIntervalMs = minIntervalMs;
        this.clock = clock;
    }

    /**
     * @return {@code true} — этот вызов разрешён (и учтён как последний для {@code key});
     *         {@code false} — тот же {@code key} уже был разрешён менее {@code minIntervalMs} назад.
     */
    public synchronized boolean allow(Object key) {
        long now = clock.millis();
        Long last = lastAllowedAt.get(key);
        if (last != null && now - last < minIntervalMs) {
            return false;
        }
        lastAllowedAt.put(key, now);
        return true;
    }
}
