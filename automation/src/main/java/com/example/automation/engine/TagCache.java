package com.example.automation.engine;

import com.example.scriptcore.TelemetryEnvelope;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Последние значения входных тегов проектов, которыми владеет экземпляр. Хранится только то,
 * на что есть подписчик: в {@code scada.tags} значения всех тегов установки.
 * <p>
 * Недостоверное чтение не затирает последнее хорошее значение и его время — только снимает
 * признак достоверности. Возраст значения по-прежнему растёт, и задача уйдёт в INPUT_STALE.
 */
@Component
public class TagCache implements TagReader {

    private final Map<String, TagReading> readings = new ConcurrentHashMap<>();
    private final Map<String, Integer> watchers = new ConcurrentHashMap<>();

    public void watch(Collection<String> tags) {
        tags.forEach(tag -> watchers.merge(tag, 1, Integer::sum));
    }

    public void unwatch(Collection<String> tags) {
        for (String tag : tags) {
            if (watchers.computeIfPresent(tag, (key, count) -> count <= 1 ? null : count - 1) == null) {
                readings.remove(tag);
            }
        }
    }

    public boolean isWatched(String tag) {
        return watchers.containsKey(tag);
    }

    public void update(String tag, TelemetryEnvelope envelope, long nowMs) {
        if (envelope.good()) {
            readings.put(tag, new TagReading(TelemetryEnvelope.coerce(envelope.value()), true, nowMs));
        } else {
            readings.computeIfPresent(tag, (key, previous) ->
                    new TagReading(previous.value(), false, previous.receivedAtMs()));
        }
    }

    @Override
    public TagReading read(String tag) {
        return readings.get(tag);
    }
}
