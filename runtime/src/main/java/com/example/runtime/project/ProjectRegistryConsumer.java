package com.example.runtime.project;

import com.example.runtime.config.KafkaProperties;
import com.example.runtime.persistence.DriverLeaseService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Читает компактный топик активных проектов, который пишет editor, и поднимает/гасит проекты.
 * <p>
 * Топик вычитывается до конца <b>до</b> того, как сервис начинает работать: иначе первые
 * секунды runtime крутил бы неполный набор проектов, и мойка, идущая на непрочитанном
 * проекте, осталась бы без хозяина.
 * <p>
 * Группа уникальна для экземпляра: топик читают все экземпляры целиком, а не делят между
 * собой — распределение проектов задаёт аренда драйверов, а не партиции.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectRegistryConsumer {

    private static final Duration POLL = Duration.ofMillis(500);

    private final KafkaProperties kafkaProperties;
    private final ProjectRuntimeService projectRuntimeService;
    private final DriverLeaseService leases;

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        thread = new Thread(this::run, "runtime-projects-consumer");
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

    /**
     * Надзорный цикл: consumer пересоздаётся после любого сбоя, пока сервис жив. Тот же приём,
     * что в TagKafkaConsumer, и по той же причине — молча переставший читать реестр проектов
     * опаснее упавшего процесса: снаружи сервис выглядит здоровым.
     */
    private void run() {
        String topic = kafkaProperties.getProjectsTopic();
        Properties props = consumerProperties();

        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
                this.consumer = c;
                c.subscribe(List.of(topic));
                log.info("Читаю реестр активных проектов из '{}', группа '{}'",
                        topic, props.getProperty(ConsumerConfig.GROUP_ID_CONFIG));
                catchUp(c);
                consumeUntilStopped(c);
            } catch (WakeupException ignored) {
                // штатная остановка через stop()
            } catch (Throwable e) {
                log.error("Потребитель реестра проектов упал, переподключаюсь через 5 с: {}", e.toString(), e);
                sleepQuietly();
            }
        }
    }

    /** Догнать конец топика: только после этого набор проектов считается полным. */
    private void catchUp(KafkaConsumer<String, String> c) {
        c.poll(Duration.ZERO);
        Set<TopicPartition> partitions = c.assignment();
        if (partitions.isEmpty()) {
            // Партиции ещё не назначены — дождёмся назначения обычным poll.
            c.poll(POLL);
            partitions = c.assignment();
        }
        c.seekToBeginning(partitions);
        Map<TopicPartition, Long> ends = c.endOffsets(partitions);
        int applied = 0;
        while (running && !reachedEnd(c, ends)) {
            ConsumerRecords<String, String> records = c.poll(POLL);
            if (records.isEmpty()) {
                break;
            }
            for (ConsumerRecord<String, String> record : records) {
                apply(record);
                applied++;
            }
        }
        log.info("Реестр активных проектов прочитан: {} записей применено", applied);
    }

    private boolean reachedEnd(KafkaConsumer<String, String> c, Map<TopicPartition, Long> ends) {
        for (Map.Entry<TopicPartition, Long> entry : ends.entrySet()) {
            if (c.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void consumeUntilStopped(KafkaConsumer<String, String> c) {
        while (running) {
            for (ConsumerRecord<String, String> record : c.poll(POLL)) {
                apply(record);
            }
        }
    }

    /**
     * Запись с телом — проект в эксплуатации, tombstone (value = null) — выведен из неё.
     * Тело намеренно не разбирается: сам факт наличия записи и есть признак.
     */
    private void apply(ConsumerRecord<String, String> record) {
        Long projectId = parseProjectId(record.key());
        if (projectId == null) {
            return;
        }
        try {
            if (record.value() == null) {
                projectRuntimeService.deactivate(projectId);
            } else {
                projectRuntimeService.activate(projectId);
            }
        } catch (Exception e) {
            log.error("Не удалось применить запись реестра для проекта {}: {}", projectId, e.toString(), e);
        }
    }

    private static Long parseProjectId(String key) {
        try {
            return key == null ? null : Long.valueOf(key.trim());
        } catch (NumberFormatException e) {
            log.warn("Ключ '{}' в реестре проектов не число — пропускаю", key);
            return null;
        }
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        // Уникальная группа: реестр нужен каждому экземпляру целиком, делить его партициями нельзя.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "runtime-projects-" + leases.instanceId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
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
