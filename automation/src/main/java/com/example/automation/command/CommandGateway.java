package com.example.automation.command;

import com.example.automation.config.AutomationProperties;
import com.example.automation.engine.CommandSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Команды в ПЛК через шлюз: {@code scada-commands} → ответ в {@code scada-command-results}.
 * Формат команды тот же, что у runtime. Результаты читаются целиком (без группы): ответ на
 * команду этого экземпляра может лежать в любой партиции; своя команда — по {@code commandId}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommandGateway implements CommandSender {

    private static final String REQUESTED_BY = "scada-automation";
    private static final long RECONNECT_DELAY_MS = 5000;

    private final AutomationProperties properties;
    private final ObjectMapper mapper;

    private final Map<String, CompletableFuture<CommandOutcome>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "automation-command-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private volatile KafkaProducer<String, String> producer;
    private volatile KafkaConsumer<String, String> consumer;
    private volatile boolean running;
    private volatile long lastResultAtMs;
    private Thread thread;

    public void start() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        // Устаревшая команда регулятора хуже никакой: быстро сдаёмся, следующий такт пришлёт свежее значение.
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        producer = new KafkaProducer<>(props);
        running = true;
        thread = new Thread(this::readResults, "automation-command-results");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
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
        expiry.shutdownNow();
        if (producer != null) {
            producer.close(Duration.ofSeconds(2));
        }
        pending.forEach((id, future) -> future.complete(
                CommandOutcome.failure("NO_CONFIRMATION", "automation останавливается")));
    }

    @Override
    public CompletableFuture<CommandOutcome> send(String tag, Object value) {
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<CommandOutcome> future = new CompletableFuture<>();
        pending.put(commandId, future);
        long timeoutMs = properties.getKafka().getCommandTimeoutMs();
        expiry.schedule(() -> complete(commandId,
                        CommandOutcome.failure("NO_CONFIRMATION", "Шлюз не ответил за " + timeoutMs + " мс")),
                timeoutMs, TimeUnit.MILLISECONDS);
        try {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("commandId", commandId);
            command.put("tagName", tag);
            command.put("value", value);
            command.put("requestedBy", REQUESTED_BY);
            command.put("timestamp", Instant.now().toString());
            producer.send(new ProducerRecord<>(properties.getKafka().getCommandsTopic(), tag,
                    mapper.writeValueAsString(command)), (metadata, error) -> {
                if (error != null) {
                    complete(commandId, CommandOutcome.failure("NOT_DELIVERED", "Брокер не принял команду: " + error.getMessage()));
                }
            });
        } catch (Exception e) {
            complete(commandId, CommandOutcome.failure("NOT_DELIVERED", "Команда не отправлена: " + e.getMessage()));
        }
        return future;
    }

    @Override
    public long lastResultAtMs() {
        return lastResultAtMs;
    }

    private void complete(String commandId, CommandOutcome outcome) {
        CompletableFuture<CommandOutcome> future = pending.remove(commandId);
        if (future != null) {
            future.complete(outcome);
        }
    }

    private void readResults() {
        String topic = properties.getKafka().getCommandResultsTopic();
        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProperties())) {
                consumer = c;
                List<TopicPartition> partitions = c.partitionsFor(topic).stream()
                        .map(info -> new TopicPartition(topic, info.partition()))
                        .toList();
                c.assign(partitions);
                c.seekToEnd(partitions);
                while (running) {
                    for (ConsumerRecord<String, String> record : c.poll(Duration.ofMillis(200))) {
                        handle(record.value());
                    }
                }
            } catch (WakeupException ignored) {
                // штатная остановка
            } catch (Throwable e) {
                log.error("Command results on '{}' failed, reconnecting in {} ms: {}", topic, RECONNECT_DELAY_MS, e.toString(), e);
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

    private void handle(String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode id = root.get("commandId");
            if (id == null || id.isNull() || !pending.containsKey(id.asText())) {
                return;
            }
            lastResultAtMs = System.currentTimeMillis();
            JsonNode success = root.get("success");
            JsonNode status = root.get("status");
            JsonNode message = root.get("message");
            complete(id.asText(), new CommandOutcome(success != null && success.asBoolean(),
                    status == null || status.isNull() ? "APPLIED" : status.asText(),
                    message == null || message.isNull() ? null : message.asText()));
        } catch (Exception e) {
            log.debug("Unparseable command result ignored: {}", e.getMessage());
        }
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }
}
