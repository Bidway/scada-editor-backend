package com.example.runtime.project;

import com.example.runtime.config.KafkaProperties;
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
 * Читает компактный топик активных проектов, который пишет editor, и поднимает/гасит проекты.
 * <p>
 * Топик вычитывается до конца <b>до</b> того, как сервис начинает работать: иначе первые
 * секунды runtime крутил бы неполный набор проектов, и мойка, идущая на непрочитанном
 * проекте, осталась бы без хозяина.
 * <p>
 * Партиции назначаются руками, без группы: топик читают все экземпляры целиком, а не делят
 * между собой — распределение проектов задаёт аренда драйверов, а не партиции.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectRegistryConsumer {

    private static final Duration POLL = Duration.ofMillis(500);

    private final KafkaProperties kafkaProperties;
    private final ProjectRuntimeService projectRuntimeService;
    private final ProjectRuntimeStore projectStore;

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
     * что в TopicConnections, и по той же причине — молча переставший читать реестр проектов
     * опаснее упавшего процесса: снаружи сервис выглядит здоровым.
     */
    private void run() {
        String topic = kafkaProperties.getProjectsTopic();

        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProperties())) {
                this.consumer = c;
                List<TopicPartition> partitions = partitionsOf(c, topic);
                // assign, а не subscribe: реестр нужен каждому экземпляру целиком, делить его
                // между экземплярами нечего. С subscribe новая группа при каждом старте ждала
                // назначения партиций десятки секунд, и догонка успевала решить, что топик пуст.
                c.assign(partitions);
                c.seekToBeginning(partitions);
                log.info("Читаю реестр активных проектов из '{}': {} партиций", topic, partitions.size());
                catchUp(c, partitions);
                consumeUntilStopped(c);
            } catch (WakeupException ignored) {
                // штатная остановка через stop()
            } catch (Throwable e) {
                log.error("Потребитель реестра проектов упал, переподключаюсь через 5 с: {}", e.toString(), e);
                sleepQuietly();
            }
        }
    }

    private static List<TopicPartition> partitionsOf(KafkaConsumer<String, String> c, String topic) {
        List<PartitionInfo> infos = c.partitionsFor(topic);
        if (infos == null || infos.isEmpty()) {
            // Топик ещё не размечен editor-ом. Бросаем в надзорный цикл: он повторит через 5 с.
            throw new IllegalStateException("Топик " + topic + " ещё не создан");
        }
        return infos.stream().map(info -> new TopicPartition(topic, info.partition())).toList();
    }

    /**
     * Догнать конец топика и применить <b>итог</b> по каждому проекту. До компактации в топике
     * лежит вся история переключений; применённая по порядку, она поднимала, гасила и снова
     * поднимала проект — с записью состояния процедур и снятием тегов посреди старта.
     */
    private void catchUp(KafkaConsumer<String, String> c, List<TopicPartition> partitions) {
        Map<TopicPartition, Long> ends = c.endOffsets(partitions);
        List<ConsumerRecord<String, String>> history = new ArrayList<>();
        while (running && !reachedEnd(c, ends)) {
            // Пустой poll не означает конец: брокер может отдать данные следующим вызовом.
            // Признак конца — только позиции, дошедшие до endOffsets.
            for (ConsumerRecord<String, String> record : c.poll(POLL)) {
                history.add(record);
            }
        }
        Map<Long, Boolean> latest = latestStates(history);
        List<Long> running = projectStore.all().stream().map(ProjectRuntime::getProjectId).toList();
        withVanished(latest, running).forEach(this::apply);
        log.info("Реестр активных проектов прочитан: {} записей, {} проектов, в эксплуатации {}",
                history.size(), latest.size(), latest.values().stream().filter(Boolean::booleanValue).count());
    }

    /**
     * Итог догонки плюс выключение поднятых проектов, о которых в топике не осталось ни одной
     * записи. Догонка идёт не только на старте: надзорный цикл пересоздаёт потребителя после
     * сбоя, и если за время простоя проект выключили, а компактация уже вычистила его tombstone,
     * ключа в топике нет вовсе — без этого проект продолжал бы крутиться выключенным (scada-dkz1).
     */
    static Map<Long, Boolean> withVanished(Map<Long, Boolean> latest, java.util.Collection<Long> running) {
        Map<Long, Boolean> result = new LinkedHashMap<>(latest);
        for (Long projectId : running) {
            result.putIfAbsent(projectId, false);
        }
        return result;
    }

    /**
     * Последнее состояние каждого проекта в порядке первого появления: запись с телом — в
     * эксплуатации, tombstone — выведен. Ключ, не являющийся числом, пропускается.
     */
    static Map<Long, Boolean> latestStates(List<ConsumerRecord<String, String>> records) {
        Map<Long, Boolean> latest = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            Long projectId = parseProjectId(record.key());
            if (projectId != null) {
                latest.put(projectId, record.value() != null);
            }
        }
        return latest;
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
                Long projectId = parseProjectId(record.key());
                if (projectId != null) {
                    apply(projectId, record.value() != null);
                }
            }
        }
    }

    /** Тело записи намеренно не разбирается: сам факт наличия записи и есть признак. */
    private void apply(Long projectId, boolean inOperation) {
        try {
            if (inOperation) {
                projectRuntimeService.activate(projectId);
            } else {
                projectRuntimeService.deactivate(projectId);
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
        // Без group.id: партиции назначаются руками (assign), офсеты не коммитятся —
        // реестр каждый старт читается с начала.
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
