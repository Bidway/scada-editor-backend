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
 * ({@link KafkaProperties#getCommandsTopic()}), откуда её забирает шлюз и пишет в
 * контроллер тем протоколом, которым этот тег читается. OPC UA или Modbus — решает
 * шлюз по своей конфигурации тега; здесь это принципиально неважно, протокол наружу
 * не торчит.
 * <p>
 * Модель тега — <b>только строка</b>: путь узла через точку, он же
 * {@code ComponentProperty.tag_id}, он же Kafka-key телеметрии. Никаких числовых
 * идентификаторов: у шлюза их два ({@code channel_id} и внутренний PK), они не равны
 * друг другу, и наружу публикуется не тот, что нужен для записи. Тело — компактный JSON:
 * <pre>{@code
 *   { "commandId": "<uuid>",     // корреляция ответа + идемпотентность на стороне шлюза
 *     "tagName":   "<path>",     // какой тег писать — адрес команды
 *     "value":     <bool|num|str>,
 *     "requestedBy": "scada-runtime",
 *     "timestamp": "<ISO-8601>" }
 * }</pre>
 * Key сообщения = тот же путь: команды по одному тегу упорядочены между собой. Адрес при
 * этом берётся из тела, а не из ключа — команда без ключа всё равно доедет и останется
 * адресуемой, тогда как потерянный ключ означал бы запись непонятно куда.
 * <p>
 * Поля {@code dataType} в команде <b>нет намеренно</b>. Тип узла знает только шлюз (он
 * же держит конфигурацию тега: BOOLEAN, FLOAT, INT16…), а из значения, пришедшего из
 * JS-скрипта, отличить INT от FLOAT невозможно. Неверная подсказка хуже отсутствующей:
 * {@code writeTag} доверяет присланному типу больше, чем конфигурации, и запись
 * отбивается сервером по несовпадению типа. Без поля шлюз берёт тип из тега.
 */
@Component
@Slf4j
public class CommandProducer {

    /** Журнала операций в объёме нет, поэтому источник команды фиксированный. */
    private static final String REQUESTED_BY = "scada-runtime";

    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;
    private final PendingCommandRegistry pendingCommands;

    private KafkaProducer<String, String> producer;

    public CommandProducer(KafkaProperties kafkaProperties,
                           ObjectMapper objectMapper,
                           PendingCommandRegistry pendingCommands) {
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
        this.pendingCommands = pendingCommands;
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
     * Результат — <b>исход записи в ПЛК</b>, а не подтверждение брокера. Ack означает
     * лишь «Kafka приняла сообщение»: команда, которую шлюз затем отбросил (канала нет,
     * узел только на чтение, контроллер отвалился), при подсчёте по ack выглядела для
     * оператора точно так же, как применённая. Теперь future завершается ответом шлюза
     * из {@code scada-command-results} — либо, если ответа нет,
     * {@link CommandOutcome#NO_CONFIRMATION} по таймауту.
     * <p>
     * Исключение наружу не выбрасывается: сбой одной команды не должен ронять ни
     * исполнение скрипта, ни применение остальных строк набора. Вызывающий, которому
     * важен исход (например {@code RecipeApplyService}), дожидается future; тем, кому
     * не важен ({@code writeTag} из скрипта), достаточно проигнорировать результат —
     * блокировки при этом не возникает.
     *
     * @param idNode путь узла через точку — наш единственный идентификатор тега
     * @param value  значение как его передал скрипт (boolean / number / string)
     * @return future с исходом команды; никогда не завершается исключением
     */
    public CompletableFuture<CommandOutcome> send(String idNode, Object value) {
        if (idNode == null || idNode.isBlank()) {
            return CompletableFuture.completedFuture(
                    CommandOutcome.failure(CommandOutcome.NO_TAG, "Свойство не привязано к тегу"));
        }
        String topic = kafkaProperties.getCommandsTopic();
        String commandId = UUID.randomUUID().toString();
        try {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("commandId", commandId);
            command.put("tagName", idNode);
            command.put("value", value);
            command.put("requestedBy", REQUESTED_BY);
            command.put("timestamp", Instant.now().toString());

            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, idNode, objectMapper.writeValueAsString(command));

            // Ожидание регистрируется ДО отправки: шлюз пишет в ПЛК синхронно и способен
            // ответить раньше, чем вернётся управление из send(). Зарегистрируй мы после —
            // результат пришёл бы на ещё не существующее ожидание и был бы отброшен как
            // чужой, а команда завершилась бы по таймауту.
            CompletableFuture<CommandOutcome> outcome = pendingCommands.awaiting(commandId, idNode);

            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.warn("Command for tag '{}' was not delivered: {}", idNode, exception.getMessage());
                    pendingCommands.complete(commandId, CommandOutcome.failure(
                            CommandOutcome.NOT_DELIVERED, "Брокер не принял команду: " + exception.getMessage()));
                } else {
                    // Ack брокера — это НЕ применение команды: дальше её должен забрать
                    // шлюз, записать в ПЛК и ответить в scada-command-results. Ожидание
                    // здесь намеренно не разрешается.
                    log.debug("Command sent: tag='{}', commandId={}", idNode, commandId);
                }
            });
            return outcome;
        } catch (Exception e) {
            log.warn("Failed to publish command for tag '{}': {}", idNode, e.getMessage());
            pendingCommands.complete(commandId, CommandOutcome.failure(
                    CommandOutcome.NOT_DELIVERED, "Не удалось сформировать команду: " + e.getMessage()));
            return CompletableFuture.completedFuture(CommandOutcome.failure(
                    CommandOutcome.NOT_DELIVERED, "Не удалось сформировать команду: " + e.getMessage()));
        }
    }

}
