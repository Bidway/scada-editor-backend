package com.example.runtime.kafka;

import com.example.runtime.assignment.AssignmentState.TopicAssignment;
import com.example.runtime.config.KafkaProperties;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Приём телеметрии и результатов команд по назначенным экземпляру топикам. Поток на топик, все
 * партиции через {@code assign}, без группы: топик назначен ровно одному экземпляру, делить его не с кем.
 * Телеметрия читается с конца — шлюз досылает значения каждый цикл опроса. Заменяет TagKafkaConsumer
 * (группа runtime-service) и CommandResultConsumer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TopicConnections {

    private static final Duration POLL = Duration.ofMillis(200);

    private final KafkaProperties kafkaProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final PendingCommandRegistry pendingCommands;
    private final ObjectMapper objectMapper;

    private final Map<String, Reader> readers = new HashMap<>();

    /** Привести открытые потоки к назначенным топикам: лишние закрыть, недостающие открыть. */
    public synchronized void sync(Collection<TopicAssignment> assignments) {
        Set<String> wanted = assignments.stream()
                .flatMap(a -> java.util.stream.Stream.of("T:" + a.telemetryTopic(), "R:" + a.resultsTopic()))
                .collect(Collectors.toSet());
        readers.keySet().removeIf(key -> {
            if (!wanted.contains(key)) {
                readers.get(key).stop();
                log.info("Отключён от топика {}", key.substring(2));
                return true;
            }
            return false;
        });
        for (TopicAssignment a : assignments) {
            open("T:" + a.telemetryTopic(), a.telemetryTopic(),
                    record -> eventPublisher.publishEvent(new KafkaTagMessageEvent(record.key(), record.value())));
            open("R:" + a.resultsTopic(), a.resultsTopic(), record -> handleResult(record.value()));
        }
    }

    @PreDestroy
    synchronized void stopAll() {
        readers.values().forEach(Reader::stop);
        readers.clear();
    }

    private void open(String key, String topic, Consumer<ConsumerRecord<String, String>> handler) {
        if (readers.containsKey(key)) {
            return;
        }
        Reader reader = new Reader(topic, handler);
        readers.put(key, reader);
        reader.start();
        log.info("Подключён к топику {}", topic);
    }

    private void handleResult(String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode id = root.get("commandId");
            if (id == null || id.isNull()) {
                return;
            }
            JsonNode success = root.get("success");
            JsonNode status = root.get("status");
            JsonNode message = root.get("message");
            CommandOutcome outcome = CommandOutcome.fromGateway(success != null && success.asBoolean(),
                    status == null || status.isNull() ? null : status.asText(),
                    message == null || message.isNull() ? null : message.asText());
            pendingCommands.complete(id.asText(), outcome);
            if (!outcome.applied()) {
                JsonNode tag = root.get("tagName");
                log.warn("Команда {} по тегу '{}' не применена: {} — {}", id.asText(),
                        tag == null ? null : tag.asText(), outcome.status(), outcome.message());
            }
        } catch (Exception e) {
            log.warn("Unparseable command result, ignoring: {}", e.getMessage());
        }
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, kafkaProperties.getMaxPollRecords());
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, kafkaProperties.getFetchMinBytes());
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, kafkaProperties.getFetchMaxWaitMs());
        return props;
    }

    /** Надзорный цикл на один топик: потребитель пересоздаётся после любого сбоя, пока топик назначен. */
    private final class Reader implements Runnable {

        private final String topic;
        private final Consumer<ConsumerRecord<String, String>> handler;
        private final Thread thread;
        private volatile boolean running = true;
        private volatile KafkaConsumer<String, String> consumer;

        private Reader(String topic, Consumer<ConsumerRecord<String, String>> handler) {
            this.topic = topic;
            this.handler = handler;
            this.thread = new Thread(this, "kafka-topic-" + topic);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

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
            thread.interrupt();
        }

        @Override
        public void run() {
            while (running) {
                try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProperties())) {
                    consumer = c;
                    List<PartitionInfo> infos = c.partitionsFor(topic);
                    if (infos == null || infos.isEmpty()) {
                        throw new IllegalStateException("Топик " + topic + " не существует");
                    }
                    List<TopicPartition> partitions = infos.stream()
                            .map(info -> new TopicPartition(topic, info.partition())).toList();
                    c.assign(partitions);
                    c.seekToEnd(partitions);
                    while (running) {
                        for (ConsumerRecord<String, String> record : c.poll(POLL)) {
                            try {
                                handler.accept(record);
                            } catch (Exception e) {
                                log.warn("Сообщение из топика {} не обработано: {}", topic, e.getMessage());
                            }
                        }
                    }
                } catch (WakeupException ignored) {
                    // штатная остановка
                } catch (Throwable e) {
                    log.error("Приём из топика {} упал, переподключаюсь через 5 с: {}", topic, e.toString(), e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } finally {
                    consumer = null;
                }
            }
        }
    }
}
