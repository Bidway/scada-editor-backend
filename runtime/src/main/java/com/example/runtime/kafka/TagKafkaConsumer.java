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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Постоянные consumer'ы единого Kafka-топика проекта (см. {@link KafkaProperties#getTagsTopic()}).
 * Контроллеры/драйверы пишут туда значения всех тегов, различая их по key сообщения
 * ({@code Node.idNode}) — поэтому в отличие от "динамической подписки на N топиков"
 * здесь достаточно долгоживущих consumer'ов, запускаемых один раз при старте приложения
 * и работающих весь его жизненный цикл (а не по refcount на сессии).
 * Прочитанные сообщения публикуются как {@link KafkaTagMessageEvent}, чтобы избежать
 * прямой зависимости от {@code TagValueRouter}.
 * <p>
 * <b>Параллелизм.</b> {@code kafka.concurrency} задаёт число рабочих потоков, каждый со
 * своим consumer'ом в общей группе; партиции топика Kafka распределяет между ними сама.
 * Порядок сообщений по каждому отдельному тегу при этом сохраняется: key сообщения — это
 * tagId, значит все сообщения одного тега лежат в одной партиции, а партиция в каждый
 * момент принадлежит ровно одному потоку. Выигрыш ограничен числом партиций топика:
 * потоки сверх него получат пустое назначение и будут простаивать.
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
    private final List<Worker> workers = new ArrayList<>();

    public TagKafkaConsumer(KafkaProperties kafkaProperties,
                            ApplicationEventPublisher eventPublisher) {
        this.kafkaProperties = kafkaProperties;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void start() {
        int concurrency = Math.max(1, kafkaProperties.getConcurrency());
        for (int i = 0; i < concurrency; i++) {
            Worker worker = new Worker(i);
            workers.add(worker);
            worker.start();
        }
        log.info("Kafka tag consumers started: {} thread(s) on topic '{}', group '{}'",
                concurrency, kafkaProperties.getTagsTopic(), kafkaProperties.getConsumerGroupId());
    }

    @PreDestroy
    void stop() {
        running = false;
        workers.forEach(Worker::stop);
    }

    /**
     * Один рабочий поток с собственным consumer'ом. Надзорный цикл пересоздаёт consumer
     * после любого сбоя, пока сервис не остановлен.
     * <p>
     * Раньше {@code catch} стоял снаружи {@code while}, и первое же исключение из
     * {@code poll()} — таймаут ребаланса, кратковременная потеря брокера — навсегда
     * завершало поток. Приложение при этом оставалось «живым»: HTTP отвечал, WebSocket-ы
     * висели открытыми, а телеметрия просто переставала идти, и оператор смотрел на
     * замерший экран, считая его актуальным. Это опаснее падения процесса, которое хотя
     * бы заметно снаружи.
     */
    private final class Worker implements Runnable {

        private final int index;
        private final Thread thread;
        private volatile KafkaConsumer<String, String> consumer;

        private Worker(int index) {
            this.index = index;
            this.thread = new Thread(this, "kafka-tags-consumer-" + index);
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void stop() {
            KafkaConsumer<String, String> c = consumer;
            if (c != null) {
                try {
                    c.wakeup();
                } catch (Exception ignored) {
                    // consumer уже закрыт надзорным циклом — будить нечего
                }
            }
            thread.interrupt(); // снимает паузу перед переподключением, если поток спит в ней
        }

        @Override
        public void run() {
            String topic = kafkaProperties.getTagsTopic();
            Properties props = consumerProperties();

            while (running) {
                try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
                    this.consumer = c;
                    c.subscribe(List.of(topic));
                    log.info("Kafka tag consumer #{} subscribed to '{}'", index, topic);
                    consumeUntilStopped(c, topic);
                } catch (WakeupException ignored) {
                    // штатная остановка через stop()
                } catch (Throwable e) {
                    // Throwable, а не Exception: сбой загрузки нативной библиотеки —
                    // UnsatisfiedLinkError, то есть Error. Так уже случилось вживую с
                    // snappy на Alpine: телеметрия шлюза сжата, разжать её не удалось,
                    // Error пролетел мимо catch(Exception) и убил поток окончательно.
                    // Приложение осталось «живым» — HTTP отвечал, WebSocket-ы висели, —
                    // а данные просто перестали идти, и оператор смотрел на замерший
                    // экран как на актуальный. Для мониторинга это хуже падения процесса.
                    // Здесь любой сбой лечится пересозданием consumer'а: если причина
                    // неустранима, она будет громко повторяться в логе раз в RECONNECT_DELAY_MS,
                    // а не молчать.
                    log.error("Kafka tag consumer #{} on topic '{}' failed, reconnecting in {} ms: {}",
                            index, topic, RECONNECT_DELAY_MS, e.toString(), e);
                } finally {
                    this.consumer = null;
                }

                if (running && !sleepBeforeReconnect()) {
                    break;
                }
            }
            log.info("Kafka tag consumer #{} on topic '{}' stopped", index, topic);
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
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getConsumerGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        // Разбор сообщения теперь дешёвый (тело не парсится до проверки подписки),
        // поэтому больший батч на poll окупается: меньше сетевых round-trip'ов.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, kafkaProperties.getMaxPollRecords());
        // Даём брокеру накопить данные вместо потока мелких ответов; верхняя граница
        // задержки — fetch.max.wait.ms, что заметно меньше интервала флаша на фронт.
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, kafkaProperties.getFetchMinBytes());
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, kafkaProperties.getFetchMaxWaitMs());
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
