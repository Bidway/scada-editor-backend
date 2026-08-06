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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Обратный конец разговора со шлюзом: читает {@code scada-command-results} и разрешает
 * ожидания в {@link PendingCommandRegistry} по {@code commandId}.
 * <p>
 * Поток один, в отличие от {@link TagKafkaConsumer}: результатов столько же, сколько
 * команд оператора, — единицы в минуту против ~1200 сообщений телеметрии в секунду.
 * <p>
 * <b>Группа своя, а не общая с телеметрией.</b> Consumer'ы одной группы должны быть
 * подписаны на одинаковый набор топиков — иначе каждый ребаланс отбирал бы партиции
 * друг у друга. Плюс своя группа означает свой оффсет: перезапуск runtime не тянет за
 * собой чужую позицию чтения.
 * <p>
 * Оффсет {@code latest}: результат команды, отправленной до перезапуска, ждать некому —
 * future, который его ждал, умер вместе с процессом. Переигрывать такие ответы значит
 * разрешать ожидания, которых уже нет.
 */
@Component
@Slf4j
public class CommandResultConsumer {

    private static final long POLL_TIMEOUT_MS = 500;
    private static final long RECONNECT_DELAY_MS = 5000;

    private final KafkaProperties kafkaProperties;
    private final PendingCommandRegistry pendingCommands;
    private final ObjectMapper objectMapper;

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public CommandResultConsumer(KafkaProperties kafkaProperties,
                                 PendingCommandRegistry pendingCommands,
                                 ObjectMapper objectMapper) {
        this.kafkaProperties = kafkaProperties;
        this.pendingCommands = pendingCommands;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::run, "kafka-command-results-consumer");
        thread.setDaemon(true);
        thread.start();
        log.info("Kafka command-result consumer started on topic '{}'",
                kafkaProperties.getCommandResultsTopic());
    }

    @PreDestroy
    void stop() {
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            try {
                c.wakeup();
            } catch (Exception ignored) {
                // consumer уже закрыт надзорным циклом — будить нечего
            }
        }
        if (thread != null) {
            thread.interrupt(); // снимает паузу перед переподключением
        }
    }

    /**
     * Надзорный цикл: пересоздаёт consumer после любого сбоя. Как и у consumer'а
     * телеметрии, {@code catch} стоит внутри {@code while} — иначе первый же таймаут
     * ребаланса навсегда обрывал бы приём подтверждений, и все команды начали бы
     * молча завершаться по таймауту как «результат неизвестен».
     */
    private void run() {
        String topic = kafkaProperties.getCommandResultsTopic();
        Properties props = consumerProperties();

        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
                this.consumer = c;
                c.subscribe(List.of(topic));
                log.info("Kafka command-result consumer subscribed to '{}'", topic);
                while (running) {
                    ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(POLL_TIMEOUT_MS));
                    for (ConsumerRecord<String, String> record : records) {
                        handle(record.value());
                    }
                }
            } catch (WakeupException ignored) {
                // штатная остановка через stop()
            } catch (Throwable e) {
                // Throwable по той же причине, что в TagKafkaConsumer: сбой нативной
                // библиотеки (snappy) приходит Error'ом и мимо catch(Exception) убил бы
                // поток навсегда — все команды начали бы завершаться по таймауту как
                // «исход неизвестен», без единой строки о причине.
                log.error("Kafka command-result consumer on topic '{}' failed, reconnecting in {} ms: {}",
                        topic, RECONNECT_DELAY_MS, e.toString(), e);
            } finally {
                this.consumer = null;
            }

            if (running && !sleepBeforeReconnect()) {
                break;
            }
        }
        log.info("Kafka command-result consumer on topic '{}' stopped", topic);
    }

    /**
     * Разбирает {@code CommandResultMessage} шлюза. Сбой разбора не должен ронять цикл
     * чтения: непонятый результат означает лишь, что команда завершится по таймауту как
     * {@link CommandOutcome#NO_CONFIRMATION}.
     */
    private void handle(String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            String commandId = text(root.get("commandId"));
            if (commandId == null) {
                return;
            }
            JsonNode success = root.get("success");
            CommandOutcome outcome = CommandOutcome.fromGateway(
                    success != null && success.asBoolean(),
                    text(root.get("status")),
                    text(root.get("message")));

            pendingCommands.complete(commandId, outcome);

            if (!outcome.applied()) {
                log.warn("Команда {} по тегу '{}' не применена: {} — {}",
                        commandId, text(root.get("tagName")), outcome.status(), outcome.message());
            }
        } catch (Exception e) {
            log.warn("Unparseable command result, ignoring: {}", e.getMessage());
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        // Группа уникальна на каждый запуск процесса: подтверждения адресованы именно
        // этому runtime и только пока он жив. Общая стабильная группа означала бы, что
        // два инстанса делят партиции и половина ответов уходит не туда, где ждёт future.
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                kafkaProperties.getConsumerGroupId() + "-command-results-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        return props;
    }

    /** @return {@code false}, если ожидание прервано и поток надо завершать */
    private static boolean sleepBeforeReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
