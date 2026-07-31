package com.example.runtime.kafka;

import com.example.runtime.config.KafkaProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Единственный постоянный consumer единого Kafka-топика проекта (см. {@link KafkaProperties#getTagsTopic()}).
 * Контроллеры/драйверы пишут туда значения всех тегов, различая их по key сообщения
 * ({@code Node.idNode}) — поэтому в отличие от "динамической подписки на N топиков"
 * здесь достаточно одного долгоживущего consumer'а, запускаемого один раз при старте
 * приложения и работающего весь его жизненный цикл (а не по refcount на сессии).
 * Прочитанные сообщения публикуются как {@link KafkaTagMessageEvent}, чтобы избежать
 * прямой зависимости от {@code TagValueRouter}.
 */
@Component
@Slf4j
public class TagKafkaConsumer {

    private static final long POLL_TIMEOUT_MS = 200;

    /** Пауза перед пересозданием consumer'а: чтобы при лежащем брокере не крутить цикл вхолостую. */
    private static final long RECONNECT_DELAY_MS = 5000;

    private final KafkaProperties kafkaProperties;
    private final ApplicationEventPublisher eventPublisher;

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public TagKafkaConsumer(KafkaProperties kafkaProperties,
                            ApplicationEventPublisher eventPublisher) {
        this.kafkaProperties = kafkaProperties;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::pollLoop, "kafka-tags-consumer");
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
                // consumer уже закрыт надзорным циклом — будить нечего
            }
        }
        if (thread != null) {
            thread.interrupt(); // снимает паузу перед переподключением, если поток спит в ней
        }
    }

    /**
     * Надзорный цикл: пересоздаёт consumer после любого сбоя, пока сервис не остановлен.
     * <p>
     * Раньше {@code catch} стоял снаружи {@code while}, и первое же исключение из
     * {@code poll()} — таймаут ребаланса, кратковременная потеря брокера — навсегда
     * завершало поток. Приложение при этом оставалось «живым»: HTTP отвечал, WebSocket-ы
     * висели открытыми, а телеметрия просто переставала идти, и оператор смотрел на
     * замерший экран, считая его актуальным. Это опаснее падения процесса, которое хотя
     * бы заметно снаружи.
     */
    private void pollLoop() {
        String topic = kafkaProperties.getTagsTopic();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getConsumerGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
                this.consumer = c;
                c.subscribe(List.of(topic));
                log.info("Started Kafka consumer for tags topic '{}'", topic);
                consumeUntilStopped(c, topic);
            } catch (WakeupException ignored) {
                // штатная остановка через stop()
            } catch (Exception e) {
                log.error("Kafka consumer for tags topic '{}' failed, will reconnect in {} ms: {}",
                        topic, RECONNECT_DELAY_MS, e.getMessage(), e);
            } finally {
                this.consumer = null;
            }

            if (running && !sleepBeforeReconnect()) {
                break;
            }
        }
        log.info("Stopped Kafka consumer for tags topic '{}'", topic);
    }

    /**
     * Внутренний цикл чтения. Выходит наружу только на остановке ({@link WakeupException})
     * либо на сбое, который лечится пересозданием consumer'а.
     */
    private void consumeUntilStopped(KafkaConsumer<String, String> c, String topic) {
        while (running) {
            ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(POLL_TIMEOUT_MS));
            for (ConsumerRecord<String, String> record : records) {
                try {
                    // Тело не разбираем: в топике значения всех тегов установки, а подписаны
                    // единицы. Распаковку делает TagValueRouter — после проверки подписки.
                    eventPublisher.publishEvent(
                            new KafkaTagMessageEvent(record.key(), record.value()));
                } catch (Exception e) {
                    log.warn("Failed to dispatch kafka message from topic {}: {}", topic, e.getMessage());
                }
            }
        }
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
