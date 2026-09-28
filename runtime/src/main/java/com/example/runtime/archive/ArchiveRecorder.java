package com.example.runtime.archive;

import com.example.runtime.kafka.KafkaTagMessageEvent;
import com.example.scriptcore.TelemetryEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Архив пишет только изменения: повтор того же значения (шлюз шлёт каждый тег каждый цикл
 * опроса, ~2600 сообщений/с на установку) в очередь не попадает.
 * <p>
 * В отличие от {@code TagValueRouter}, разбирает <b>каждое</b> сообщение топика, а не только
 * подписанные теги: архиву нужны все, иначе воспроизведение и тренд не работали бы для тега,
 * который сегодня ни на одной сцене. Разбор конверта — микросекунды, в БД этот поток не ходит.
 * <p>
 * Точка пишется, если: тега ещё нет в памяти (первая после старта); сменилось качество; при
 * GOOD сменилось значение (сравниваются приведённые значения — «1» и «1.0» равны); начались
 * новые сутки (опорная точка — «значение на момент» ищется в пределах одной секции).
 */
@Component
public class ArchiveRecorder {

    private record Written(Object value, boolean good, LocalDate day, long ts) {
    }

    private final ArchiveProperties props;
    private final ObjectMapper mapper;
    private final ZoneId zone;
    private final BoundedQueue<ArchivePoint> queue;
    private final Map<String, Written> lastWritten = new ConcurrentHashMap<>();

    @Autowired
    public ArchiveRecorder(ArchiveProperties props, ObjectMapper mapper) {
        this(props, mapper, ZoneId.systemDefault());
    }

    /** Для тестов: пояс, по которому считаются сутки опорной точки. */
    public ArchiveRecorder(ArchiveProperties props, ObjectMapper mapper, ZoneId zone) {
        this.props = props;
        this.mapper = mapper;
        this.zone = zone;
        this.queue = new BoundedQueue<>(props.getQueueCapacity());
    }

    public BoundedQueue<ArchivePoint> queue() {
        return queue;
    }

    @EventListener
    public void onMessage(KafkaTagMessageEvent event) {
        if (!props.isEnabled() || event.key() == null) {
            return;
        }
        // Битый конверт: TagValueRouter уже пишет о нём warn — второй на каждое сообщение не нужен.
        TelemetryEnvelope envelope = TelemetryEnvelope.parse(mapper, event.rawValue(), e -> { });
        record(event.key(), envelope, System.currentTimeMillis());
    }

    void record(String tag, TelemetryEnvelope envelope, long nowMs) {
        boolean good = envelope.good();
        Object value = good ? TelemetryEnvelope.coerce(envelope.value()) : null;
        LocalDate day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();

        Written prev = lastWritten.get(tag);
        boolean changed = prev == null
                || prev.good() != good
                || (good && !Objects.equals(prev.value(), value))
                || !prev.day().equals(day);
        if (!changed) {
            return;
        }
        // Курсор воспроизведения идёт по (ts, tag): две точки тега в одну миллисекунду
        // (смена качества и значение подряд) иначе стали бы неразличимы.
        long ts = prev != null && nowMs <= prev.ts() ? prev.ts() + 1 : nowMs;
        ArchivePoint point = toPoint(tag, ts, value, good);
        if (queue.offer(point)) {
            lastWritten.put(tag, new Written(value, good, day, ts));
        } else {
            // Не помнить сброшенное как записанное: иначе то же значение не записалось бы никогда.
            lastWritten.remove(tag);
        }
    }

    private static ArchivePoint toPoint(String tag, long ts, Object value, boolean good) {
        if (!good || value == null) {
            return new ArchivePoint(tag, ts, null, null, good);
        }
        if (value instanceof Boolean b) {
            return new ArchivePoint(tag, ts, b ? 1.0 : 0.0, null, true);
        }
        if (value instanceof Number n) {
            return new ArchivePoint(tag, ts, n.doubleValue(), null, true);
        }
        return new ArchivePoint(tag, ts, null, value.toString(), true);
    }
}
