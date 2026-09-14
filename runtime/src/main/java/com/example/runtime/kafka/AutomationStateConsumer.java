package com.example.runtime.kafka;

import com.example.runtime.config.KafkaProperties;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.session.VariableTags;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Читает {@code automation.state} целиком (все партиции, без группы, с начала топика): каждый
 * экземпляр runtime обязан знать текущие значения всех переменных и статусы всех задач.
 * <ul>
 *   <li>{@code var:<projectId>:<имя>} — публикуется как обычное сообщение телеметрии: подписка,
 *       снимок, кадр и onChange идут через {@code TagValueRouter}. Последнее значение хранится,
 *       чтобы новая сессия получила его сразу, а не ждала изменения (переменная может не меняться часами).</li>
 *   <li>{@code status:<projectId>:<taskId>} — хранится и уходит в кадр {@code tasks} сессиям проекта,
 *       подписанным на статусы.</li>
 * </ul>
 */
@Component
@Slf4j
public class AutomationStateConsumer {

    private static final String STATUS_PREFIX = "status:";
    private static final long RECONNECT_DELAY_MS = 5000;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final KafkaProperties kafkaProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final RuntimeSessionStore sessionStore;
    private final ObjectMapper objectMapper;

    private final Map<String, String> variables = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, Map<String, Object>>> statuses = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public AutomationStateConsumer(KafkaProperties kafkaProperties, ApplicationEventPublisher eventPublisher,
                                   RuntimeSessionStore sessionStore, ObjectMapper objectMapper) {
        this.kafkaProperties = kafkaProperties;
        this.eventPublisher = eventPublisher;
        this.sessionStore = sessionStore;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::run, "kafka-automation-state");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() {
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

    /** Отдать новой сессии текущие значения её переменных — вызывать сразу после регистрации сессии. */
    public void replayVariables(RuntimeSession session) {
        for (String tagId : session.getIndex().getAllTagIds()) {
            if (VariableTags.isVariableKey(tagId)) {
                String raw = variables.get(tagId);
                if (raw != null) {
                    eventPublisher.publishEvent(new KafkaTagMessageEvent(tagId, raw));
                }
            }
        }
    }

    public List<Map<String, Object>> statusesOf(Long projectId) {
        return List.copyOf(statuses.getOrDefault(projectId, Map.of()).values());
    }

    private void run() {
        String topic = kafkaProperties.getAutomationStateTopic();
        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProperties())) {
                consumer = c;
                List<TopicPartition> partitions = c.partitionsFor(topic).stream()
                        .map(info -> new TopicPartition(topic, info.partition()))
                        .toList();
                c.assign(partitions);
                c.seekToBeginning(partitions);
                log.info("Automation state consumer assigned {} partition(s) of '{}'", partitions.size(), topic);
                while (running) {
                    for (ConsumerRecord<String, String> record : c.poll(Duration.ofMillis(500))) {
                        handle(record.key(), record.value());
                    }
                }
            } catch (WakeupException ignored) {
                // штатная остановка
            } catch (Throwable e) {
                log.error("Automation state consumer on '{}' failed, reconnecting in {} ms: {}",
                        topic, RECONNECT_DELAY_MS, e.toString(), e);
            } finally {
                consumer = null;
            }
            if (running) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handle(String key, String value) {
        if (key == null) {
            return;
        }
        if (VariableTags.isVariableKey(key)) {
            if (value == null) {
                variables.remove(key);
                return;
            }
            variables.put(key, value);
            eventPublisher.publishEvent(new KafkaTagMessageEvent(key, value));
        } else if (key.startsWith(STATUS_PREFIX)) {
            handleStatus(key, value);
        }
    }

    private void handleStatus(String key, String value) {
        String[] parts = key.split(":");
        if (parts.length != 3) {
            return;
        }
        try {
            long projectId = Long.parseLong(parts[1]);
            long taskId = Long.parseLong(parts[2]);
            if (value == null) {
                statuses.getOrDefault(projectId, new ConcurrentHashMap<>()).remove(taskId);
                return;
            }
            Map<String, Object> status = objectMapper.readValue(value, MAP);
            statuses.computeIfAbsent(projectId, id -> new ConcurrentHashMap<>()).put(taskId, status);
            for (RuntimeSession session : sessionStore.all()) {
                if (Long.valueOf(projectId).equals(session.getProjectId()) && session.isTasksSubscribed()) {
                    session.getOutboundBuffer().offerTask(status);
                }
            }
        } catch (Exception e) {
            log.debug("Unreadable automation status '{}': {}", key, e.getMessage());
        }
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }
}
