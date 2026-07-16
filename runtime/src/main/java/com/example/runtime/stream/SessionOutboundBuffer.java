package com.example.runtime.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Копится в реальном времени из Kafka-consumer тредов, вычитывается раз в
 * flush-interval-ms из OutboundFlusher одним батчем на сессию — так много часто
 * обновляющихся тегов не превращаются в лавину отдельных WS-фреймов.
 * <p>
 * Флашер не дренирует буфер, пока не подключён WS, поэтому буфер ограничен сверху:
 * у сессии, которую создали и не подключили, он иначе рос бы без предела. При
 * переполнении отбрасываются самые старые записи — для мониторинга свежее значение
 * важнее пропущенного старого.
 */
public class SessionOutboundBuffer {

    private static final int MAX_QUEUED_PER_KIND = 10_000;

    private final Queue<TagUpdate> tagUpdates = new ConcurrentLinkedQueue<>();
    private final Queue<PropertyUpdate> propertyUpdates = new ConcurrentLinkedQueue<>();

    /** ConcurrentLinkedQueue.size() — O(n), поэтому длину считаем отдельно. */
    private final AtomicInteger tagCount = new AtomicInteger();
    private final AtomicInteger propertyCount = new AtomicInteger();

    public void offerTag(TagUpdate update) {
        tagUpdates.add(update);
        if (tagCount.incrementAndGet() > MAX_QUEUED_PER_KIND && tagUpdates.poll() != null) {
            tagCount.decrementAndGet();
        }
    }

    public void offerProperty(PropertyUpdate update) {
        propertyUpdates.add(update);
        if (propertyCount.incrementAndGet() > MAX_QUEUED_PER_KIND && propertyUpdates.poll() != null) {
            propertyCount.decrementAndGet();
        }
    }

    public boolean isEmpty() {
        return tagUpdates.isEmpty() && propertyUpdates.isEmpty();
    }

    /** Забирает всё накопленное и очищает буфер. Вызывается только флашером. */
    public Drained drainAll() {
        List<TagUpdate> tags = new ArrayList<>();
        List<PropertyUpdate> properties = new ArrayList<>();
        TagUpdate t;
        while ((t = tagUpdates.poll()) != null) {
            tags.add(t);
            tagCount.decrementAndGet();
        }
        PropertyUpdate p;
        while ((p = propertyUpdates.poll()) != null) {
            properties.add(p);
            propertyCount.decrementAndGet();
        }
        return new Drained(tags, properties);
    }

    public record Drained(List<TagUpdate> tags, List<PropertyUpdate> properties) {
        public boolean isEmpty() {
            return tags.isEmpty() && properties.isEmpty();
        }
    }
}
