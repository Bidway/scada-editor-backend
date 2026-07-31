package com.example.runtime.kafka;

import com.example.runtime.config.KafkaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Обратное направление: запись значения тега в ПЛК. Команда уходит в топик команд
 * ({@link KafkaProperties#getCommandsTopic()}), откуда её забирает драйвер устройства
 * и пишет в контроллер тем протоколом, которым этот тег читается (OPC UA или Modbus —
 * решается на стороне драйвера по {@code idNode}, здесь это неважно).
 * <p>
 * Модель тега — <b>только строка</b>: {@code idNode} (путь узла через точку, он же
 * {@code ComponentProperty.tag_id}, он же Kafka-key телеметрии). Никаких числовых
 * идентификаторов: команда самодостаточна и описывает тег ровно так же, как телеметрия
 * его называет. Тело — компактный JSON:
 * <pre>{@code
 *   { "commandId": "<uuid>",     // идемпотентность/трассировка
 *     "idNode":    "<path>",     // какой тег писать (= ключ сообщения)
 *     "value":     <bool|num|str>,
 *     "dataType":  "BOOLEAN|NUMBER|STRING",  // подсказка драйверу, выведена из значения
 *     "requestedBy": "scada-runtime",
 *     "timestamp": "<ISO-8601>" }
 * }</pre>
 * Key сообщения = {@code idNode} — команды по одному тегу упорядочены между собой.
 */
@Component
@Slf4j
public class CommandProducer {

    /** Журнала операций в объёме нет, поэтому источник команды фиксированный. */
    private static final String REQUESTED_BY = "scada-runtime";

    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;

    private KafkaProducer<String, String> producer;

    public CommandProducer(KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        // Команда оператора не должна залипать в ретраях: лучше быстро сообщить об
        // ошибке в лог, чем молча дослать её через полминуты.
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        this.producer = new KafkaProducer<>(props);
        log.info("CommandProducer initialized for topic '{}'", kafkaProperties.getCommandsTopic());
    }

    @PreDestroy
    void shutdown() {
        if (producer != null) {
            producer.close();
        }
    }

    /**
     * Отправляет команду записи тега.
     * <p>
     * Результат — <b>подтверждение брокера</b>, а не факт постановки в буфер: future
     * завершается {@code true} только после ack. Раньше метод возвращал {@code true}
     * сразу после {@code producer.send(...)}, и вызывающий считал команду применённой
     * ещё до того, как она ушла из процесса, — сбой доставки всплывал в колбэке уже
     * после ответа оператору.
     * <p>
     * Исключение наружу не выбрасывается: сбой одной команды не должен ронять ни
     * исполнение скрипта, ни применение остальных строк набора. Вызывающий, которому
     * важен исход (например {@code RecipeApplyService}), дожидается future; тем, кому
     * не важен ({@code writeTag} из скрипта), достаточно проигнорировать результат —
     * блокировки при этом не возникает.
     *
     * @param idNode путь узла через точку — наш единственный идентификатор тега
     * @param value  значение как его передал скрипт (boolean / number / string)
     * @return future с {@code true}, если брокер подтвердил запись
     */
    public CompletableFuture<Boolean> send(String idNode, Object value) {
        if (idNode == null || idNode.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String topic = kafkaProperties.getCommandsTopic();
        try {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("commandId", UUID.randomUUID().toString());
            command.put("idNode", idNode);
            command.put("value", value);
            command.put("dataType", dataTypeOf(value));
            command.put("requestedBy", REQUESTED_BY);
            command.put("timestamp", Instant.now().toString());

            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, idNode, objectMapper.writeValueAsString(command));

            CompletableFuture<Boolean> acknowledged = new CompletableFuture<>();
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.warn("Command for tag '{}' was not delivered: {}", idNode, exception.getMessage());
                    acknowledged.complete(false);
                } else {
                    log.debug("Command sent: idNode='{}'", idNode);
                    acknowledged.complete(true);
                }
            });
            return acknowledged;
        } catch (Exception e) {
            log.warn("Failed to publish command for tag '{}': {}", idNode, e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    /** Обобщённая подсказка типа для драйвера — по фактическому Java-типу значения. */
    private static String dataTypeOf(Object value) {
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        if (value instanceof Number) {
            return "NUMBER";
        }
        return "STRING";
    }
}
