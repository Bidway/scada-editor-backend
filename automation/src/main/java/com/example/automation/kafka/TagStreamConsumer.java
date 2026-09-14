package com.example.automation.kafka;

import com.example.automation.config.AutomationProperties;
import com.example.automation.engine.TagCache;
import com.example.scriptcore.TelemetryEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Читает {@code scada.tags} целиком: assign всех партиций, без consumer group. Каждый экземпляр
 * обязан видеть все теги — входы его проектов могут лежать в любой партиции. Разбирается только
 * то, на что подписан {@link TagCache}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TagStreamConsumer {

    private static final long RECONNECT_DELAY_MS = 5000;

    private final AutomationProperties properties;
    private final TagCache cache;
    private final ObjectMapper mapper;

    private volatile boolean running;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public void start() {
        running = true;
        thread = new Thread(this::run, "automation-tags");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            try {
                c.wakeup();
            } catch (Exception ignored) {
                // уже закрыт
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void run() {
        String topic = properties.getKafka().getTagsTopic();
        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProperties())) {
                consumer = c;
                List<TopicPartition> partitions = c.partitionsFor(topic).stream()
                        .map(info -> new TopicPartition(topic, info.partition()))
                        .toList();
                c.assign(partitions);
                c.seekToEnd(partitions);
                log.info("Tag stream assigned {} partition(s) of '{}'", partitions.size(), topic);
                while (running) {
                    for (ConsumerRecord<String, String> record : c.poll(Duration.ofMillis(200))) {
                        if (record.key() == null || !cache.isWatched(record.key())) {
                            continue;
                        }
                        cache.update(record.key(), TelemetryEnvelope.parse(mapper, record.value(),
                                        e -> log.debug("Tag '{}': malformed envelope: {}", record.key(), e.getMessage())),
                                System.currentTimeMillis());
                    }
                }
            } catch (WakeupException ignored) {
                // штатная остановка
            } catch (Throwable e) {
                // Throwable: Error нативной библиотеки (snappy) иначе убил бы поток молча — см. runtime.
                log.error("Tag stream on '{}' failed, reconnecting in {} ms: {}", topic, RECONNECT_DELAY_MS, e.toString(), e);
            } finally {
                consumer = null;
            }
            if (running && !sleep()) {
                break;
            }
        }
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);
        return props;
    }

    private static boolean sleep() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
