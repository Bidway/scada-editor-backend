package com.example.runtime.script;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.graalvm.polyglot.PolyglotException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Отказы скриптов, видимые не только в логе (scada-u8v). Раньше отказ ACTION, onChange или записи
 * тега оставлял одну строку {@code log.warn}: кнопка на мнемосхеме выглядела сработавшей, а
 * инженер узнавал о проблеме, только если читал лог нужного экземпляра в нужную минуту.
 * <p>
 * Хранилище — ограниченный буфер в памяти ({@value #CAPACITY} последних записей, старые
 * вытесняются) плюс счётчик Micrometer {@code runtime.script.failures} с тегом {@code kind}.
 * У runtime нет своей схемы под журнал отказов, и заводить её ради диагностики не стоит: буфер
 * живёт до перезапуска, счётчик уходит в метрики. Читается через
 * {@code GET /api/runtime/diagnostics/script-failures} — по каждому экземпляру отдельно.
 */
@Component
public class ScriptFailureRegistry {

    static final int CAPACITY = 200;

    public enum Kind {
        /** Скрипт не уложился в таймаут и был прерван. */
        TIMEOUT,
        /** Скрипт упал с ошибкой. */
        RUNTIME_ERROR,
        /** Запись тега не дошла: свойство не привязано к тегу или шлюз отказал. */
        WRITE_REJECTED,
        /** Повторный ACTION отброшен окном дедупликации. */
        DEDUP_DROPPED,
        /** Задача onChange отброшена: очередь полосы переполнена. */
        QUEUE_DROPPED
    }

    /**
     * @param projectId проект, если известен (у переполнения очереди — нет)
     * @param source    откуда отказ: {@code action script 12}, {@code onChange property 34},
     *                  {@code writeTag('V1')} и т.п.
     */
    public record Failure(Instant at, Kind kind, Long projectId, String source, String message) {
    }

    private final Deque<Failure> recent = new ArrayDeque<>(CAPACITY);
    private final Map<Kind, Counter> counters = new EnumMap<>(Kind.class);

    public ScriptFailureRegistry(MeterRegistry meterRegistry) {
        for (Kind kind : Kind.values()) {
            counters.put(kind, Counter.builder("runtime.script.failures")
                    .description("Отказы скриптов runtime по виду")
                    .tag("kind", kind.name())
                    .register(meterRegistry));
        }
    }

    public void record(Kind kind, Long projectId, String source, String message) {
        counters.get(kind).increment();
        Failure failure = new Failure(Instant.now(), kind, projectId, source, message);
        synchronized (recent) {
            if (recent.size() == CAPACITY) {
                recent.removeLast();
            }
            recent.addFirst(failure);
        }
    }

    /** Отказы, свежие первыми. */
    public List<Failure> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    /**
     * Таймаут или ошибка скрипта. {@link ScriptEngineService} при таймауте либо не дожидается
     * потока ({@link TimeoutException}), либо сторож закрывает контекст, и скрипт падает с
     * отменённым {@link PolyglotException} — оба лежат в цепочке причин.
     */
    public static Kind kindOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || (t instanceof PolyglotException pe && pe.isCancelled())) {
                return Kind.TIMEOUT;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Kind.RUNTIME_ERROR;
    }
}
