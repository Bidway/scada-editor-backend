package com.example.editor.service.automation;

import com.example.editor.config.AutomationKafkaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Синхронная публикация: строка outbox помечается отправленной только после подтверждения всеми
 * репликами ({@code acks=all}). Идемпотентный producer не задвоит сообщение при повторе внутри send.
 */
@Component
@RequiredArgsConstructor
public class AutomationDefinitionsPublisher {

    private final AutomationKafkaProperties properties;
    private final ObjectMapper objectMapper;

    private KafkaProducer<String, String> producer;

    @PostConstruct
    void init() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        this.producer = new KafkaProducer<>(props);
    }

    @PreDestroy
    void shutdown() {
        if (producer != null) {
            producer.close();
        }
    }

    /** @param payload {@code null} — tombstone: ключ проекта стирается при компакции */
    public void publish(Long projectId, JsonNode payload) throws Exception {
        String value = payload == null ? null : objectMapper.writeValueAsString(payload);
        producer.send(new ProducerRecord<>(properties.getDefinitionsTopic(), String.valueOf(projectId), value))
                .get(15, TimeUnit.SECONDS);
    }
}
