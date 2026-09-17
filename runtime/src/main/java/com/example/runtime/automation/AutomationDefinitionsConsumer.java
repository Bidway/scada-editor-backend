package com.example.runtime.automation;

import com.example.runtime.automation.definition.ProjectDefinitions;
import com.example.runtime.automation.engine.AutomationEngine;
import com.example.runtime.config.KafkaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Читает {@code automation.definitions} целиком: все партиции, без группы, с начала. Раньше
 * топик делили экземпляры сервиса automation через группу Kafka; теперь какие проекты исполнять,
 * решает runtime (проект поднят), а определения нужны все.
 * <p>
 * Непонятное определение не останавливает работающие задачи проекта: запись пропускается.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutomationDefinitionsConsumer {

    private static final Duration POLL = Duration.ofMillis(500);

    private final KafkaProperties kafkaProperties;
    private final AutomationEngine engine;
    private final ObjectMapper mapper;

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        thread = new Thread(this::run, "automation-definitions");
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
                // уже закрыт надзорным циклом — будить нечего
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Надзорный цикл: потребитель пересоздаётся после любого сбоя, пока сервис жив. */
    private void run() {
        String topic = kafkaProperties.getAutomationDefinitionsTopic();
        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProperties())) {
                consumer = c;
                List<PartitionInfo> infos = c.partitionsFor(topic);
                if (infos == null || infos.isEmpty()) {
                    throw new IllegalStateException("Топик " + topic + " ещё не создан");
                }
                List<TopicPartition> partitions = infos.stream()
                        .map(info -> new TopicPartition(topic, info.partition())).toList();
                c.assign(partitions);
                c.seekToBeginning(partitions);
                log.info("Читаю определения фоновых задач из '{}': {} партиций", topic, partitions.size());
                while (running) {
                    for (ConsumerRecord<String, String> record : c.poll(POLL)) {
                        apply(record);
                    }
                }
            } catch (WakeupException ignored) {
                // штатная остановка
            } catch (Throwable e) {
                log.error("Потребитель определений упал, переподключаюсь через 5 с: {}", e.toString(), e);
                sleepQuietly();
            }
        }
    }

    /**
     * История топика применяется по порядку: определения не гасят поднятый проект, а лишь
     * перезапускают его задачи, и промежуточная версия безвредна. Определения могут прийти раньше,
     * чем реестр проектов поднимет проект, — тогда движок только запоминает их, а задачи запустит
     * подъём проекта.
     */
    private void apply(ConsumerRecord<String, String> record) {
        Long projectId = parseProjectId(record.key());
        if (projectId == null) {
            return;
        }
        ProjectDefinitions definitions = null;
        if (record.value() != null) {
            try {
                definitions = mapper.readValue(record.value(), ProjectDefinitions.class);
            } catch (Exception e) {
                log.error("Проект {}: определения на смещении {} не читаются, оставляю прежние: {}",
                        projectId, record.offset(), e.getMessage());
                return;
            }
        }
        try {
            engine.definitionsChanged(projectId, definitions);
        } catch (Exception e) {
            log.error("Проект {}: определения не применены: {}", projectId, e.toString(), e);
        }
    }

    private static Long parseProjectId(String key) {
        try {
            return key == null ? null : Long.valueOf(key.trim());
        } catch (NumberFormatException e) {
            return null;
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

    private static void sleepQuietly() {
        try {
            Thread.sleep(5000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
