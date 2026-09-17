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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Читает {@code automation.definitions} целиком: все партиции, без группы, с начала. При каждом
 * подключении сначала догоняет конец топика и применяет только <b>итог</b> по каждому проекту, затем
 * читает поток. Раньше
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
                catchUp(c, partitions);
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
     * Догнать конец топика и применить итог по каждому проекту. Применённая по порядку, история
     * перезапускала задачи поднятого проекта на каждой старой версии: проект поднимается назначением
     * сразу после старта, раньше, чем чтение доходит до конца (scada-hssy, на стенде 7 перезапусков
     * подряд, регулятор стартовал с устаревших определений).
     */
    private void catchUp(KafkaConsumer<String, String> c, List<TopicPartition> partitions) {
        Map<TopicPartition, Long> ends = c.endOffsets(partitions);
        List<ConsumerRecord<String, String>> history = new ArrayList<>();
        while (running && !reachedEnd(c, ends)) {
            // Пустой poll не означает конец: признак конца — только позиции, дошедшие до endOffsets.
            for (ConsumerRecord<String, String> record : c.poll(POLL)) {
                history.add(record);
            }
        }
        Map<Long, ProjectDefinitions> latest = latestDefinitions(history);
        latest.forEach(this::applyDefinitions);
        log.info("Определения фоновых задач прочитаны: {} записей, {} проектов", history.size(), latest.size());
    }

    /**
     * Последние читаемые определения каждого проекта; {@code null} — tombstone. Нечитаемая запись
     * не затирает прежнюю версию, как и в потоке.
     */
    Map<Long, ProjectDefinitions> latestDefinitions(List<ConsumerRecord<String, String>> records) {
        Map<Long, ProjectDefinitions> latest = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            Long projectId = parseProjectId(record.key());
            if (projectId == null) {
                continue;
            }
            if (record.value() == null) {
                latest.put(projectId, null);
                continue;
            }
            ProjectDefinitions definitions = parse(projectId, record);
            if (definitions != null) {
                latest.put(projectId, definitions);
            }
        }
        return latest;
    }

    private static boolean reachedEnd(KafkaConsumer<String, String> c, Map<TopicPartition, Long> ends) {
        for (Map.Entry<TopicPartition, Long> entry : ends.entrySet()) {
            if (c.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Запись потока после догонки. Определения могут прийти раньше, чем проект поднимется, — тогда
     * движок только запоминает их, а задачи запустит подъём проекта.
     */
    private void apply(ConsumerRecord<String, String> record) {
        Long projectId = parseProjectId(record.key());
        if (projectId == null) {
            return;
        }
        ProjectDefinitions definitions = null;
        if (record.value() != null) {
            definitions = parse(projectId, record);
            if (definitions == null) {
                return;
            }
        }
        applyDefinitions(projectId, definitions);
    }

    /** @return {@code null}, если определения не читаются — прежние остаются */
    private ProjectDefinitions parse(long projectId, ConsumerRecord<String, String> record) {
        try {
            return mapper.readValue(record.value(), ProjectDefinitions.class);
        } catch (Exception e) {
            log.error("Проект {}: определения на смещении {} не читаются, оставляю прежние: {}",
                    projectId, record.offset(), e.getMessage());
            return null;
        }
    }

    private void applyDefinitions(Long projectId, ProjectDefinitions definitions) {
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
