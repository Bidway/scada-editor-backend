package com.example.runtime.kafka;

import com.example.runtime.config.KafkaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Единственный постоянный consumer единого Kafka-топика проекта (см. {@link KafkaProperties#getTagsTopic()}).
 * Контроллеры/драйверы пишут туда значения всех тегов, различая их по key сообщения
 * ({@code Node.idNode}) — поэтому в отличие от "динамической подписки на N топиков"
 * здесь достаточно одного долгоживущего consumer'а, запускаемого один раз при старте
 * приложения и работающего весь его жизненный цикл (а не по refcount на сессии).
 * Прочитанные сообщения публикуются как {@link KafkaTagMessageEvent}, чтобы избежать
 * прямой зависимости от {@code TagValueRouter}.
 */
@Component
@Slf4j
public class TagKafkaConsumer {

    private final KafkaProperties kafkaProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public TagKafkaConsumer(KafkaProperties kafkaProperties,
                            ApplicationEventPublisher eventPublisher) {
        this.kafkaProperties = kafkaProperties;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::pollLoop, "kafka-tags-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            c.wakeup();
        }
    }

    private void pollLoop() {
        String topic = kafkaProperties.getTagsTopic();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getConsumerGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            this.consumer = c;
            c.subscribe(List.of(topic));
            log.info("Started Kafka consumer for tags topic '{}'", topic);
            while (running) {
                ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        eventPublisher.publishEvent(
                                new KafkaTagMessageEvent(record.key(), extractValue(record.value())));
                    } catch (Exception e) {
                        log.warn("Failed to dispatch kafka message from topic {}: {}", topic, e.getMessage());
                    }
                }
            }
        } catch (WakeupException ignored) {
            // штатная остановка
        } catch (Exception e) {
            log.error("Kafka consumer for tags topic '{}' failed: {}", topic, e.getMessage(), e);
        } finally {
            log.info("Stopped Kafka consumer for tags topic '{}'", topic);
        }
    }

    /**
     * Достаёт значение тега из сообщения. В топике сосуществуют два формата:
     * драйвер устройства шлёт JSON-конверт (значение в поле {@code value}), а ручные
     * публикации — голый скаляр. Конверт распаковывается, всё остальное отдаётся как
     * есть, поэтому дальше по цепочке значение всегда остаётся сырой строкой
     * ({@code "72.7"}, {@code "true"}) — модель тега целиком строковая.
     */
    private String extractValue(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '{') {
            return raw;
        }
        try {
            JsonNode value = objectMapper.readTree(raw).get("value");
            if (value == null) {
                return raw;
            }
            return value.isNull() ? null : value.asText();
        } catch (Exception e) {
            return raw;
        }
    }
}
