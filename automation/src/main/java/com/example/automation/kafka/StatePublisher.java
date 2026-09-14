package com.example.automation.kafka;

import com.example.automation.config.AutomationProperties;
import com.example.automation.engine.TaskStatusUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Переменные и статусы задач в compacted-топик {@code automation.state}: runtime показывает их
 * мониторам. Переменная — в том же конверте, что телеметрия тегов, чтобы runtime разбирал её тем же кодом.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StatePublisher {

    private final AutomationProperties properties;
    private final ObjectMapper mapper;

    private volatile KafkaProducer<String, String> producer;

    public void start() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        producer = new KafkaProducer<>(props);
    }

    public void stop() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(2));
        }
    }

    public void publishVariable(long projectId, String name, Object value) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("value", value);
        envelope.put("quality", "GOOD");
        envelope.put("timestamp", Instant.now().toString());
        send("var:" + projectId + ":" + name, envelope);
    }

    public void publishStatus(TaskStatusUpdate update, String owner) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("taskId", update.taskId());
        status.put("name", update.name());
        status.put("state", update.state().name());
        status.put("lastRunAt", update.lastRunAtMs());
        status.put("lastDurationMs", update.lastDurationMs());
        status.put("lastError", update.lastError());
        status.put("errorCount", update.errorCount());
        status.put("owner", owner);
        send("status:" + update.projectId() + ":" + update.taskId(), status);
    }

    private void send(String key, Map<String, Object> value) {
        KafkaProducer<String, String> p = producer;
        if (p == null) {
            return;
        }
        try {
            p.send(new ProducerRecord<>(properties.getKafka().getStateTopic(), key, mapper.writeValueAsString(value)),
                    (metadata, error) -> {
                        if (error != null) {
                            log.debug("State '{}' not published: {}", key, error.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.debug("State '{}' not published: {}", key, e.getMessage());
        }
    }
}
