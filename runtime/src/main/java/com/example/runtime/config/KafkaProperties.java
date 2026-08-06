package com.example.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Единый Kafka-топик на весь проект: контроллеры/драйверы пишут туда значения всех тегов,
 * а runtime различает их по key сообщения (= {@code Node.idNode} — см.
 * {@code TagValueRouter}). Отдельного топика на тег/узел не заводится.
 */
@Component
@ConfigurationProperties(prefix = "kafka")
public class KafkaProperties {

    private String bootstrapServers = "localhost:9092";
    private String consumerGroupId = "runtime-service";
    private String tagsTopic = "scada.tags";
    /** Топик команд шлюза (обратное направление: запись тега в ПЛК). Имя задаёт шлюз. */
    private String commandsTopic = "scada-commands";

    /** Топик исходов команд: шлюз отвечает сюда, применилась запись в ПЛК или нет. */
    private String commandResultsTopic = "scada-command-results";

    /**
     * Сколько ждать ответа шлюза на команду, прежде чем считать исход неизвестным.
     * <p>
     * Шлюз пишет в ПЛК синхронно и отвечает быстро — как при успехе, так и при отказе
     * (отсутствующий канал он отбивает вообще без обращения к контроллеру). Секунды
     * здесь — запас на очередь в топике, а не на работу с железом. Значение должно
     * оставаться заметно меньше дедлайна применения набора, иначе отчёт оператору
     * упрётся в свой таймаут раньше, чем разрешатся ожидания отдельных команд.
     */
    private long commandTimeoutMs = 5000;

    /**
     * Сколько потоков читают топик тегов. Порядок по каждому тегу сохраняется при любом
     * значении: key сообщения — tagId, поэтому один тег всегда лежит в одной партиции.
     * Реальный выигрыш ограничен числом партиций топика — потоки сверх него простаивают.
     */
    private int concurrency = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    /** Верхняя граница батча на один poll. */
    private int maxPollRecords = 2000;

    /** Не будить consumer ради нескольких байт: копим ответ до этого размера или до fetchMaxWaitMs. */
    private int fetchMinBytes = 65536;

    /** Потолок задержки, которую вносит fetchMinBytes. Держим заметно ниже интервала флаша на фронт. */
    private int fetchMaxWaitMs = 50;

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getConsumerGroupId() {
        return consumerGroupId;
    }

    public void setConsumerGroupId(String consumerGroupId) {
        this.consumerGroupId = consumerGroupId;
    }

    public String getTagsTopic() {
        return tagsTopic;
    }

    public void setTagsTopic(String tagsTopic) {
        this.tagsTopic = tagsTopic;
    }

    public String getCommandsTopic() {
        return commandsTopic;
    }

    public void setCommandsTopic(String commandsTopic) {
        this.commandsTopic = commandsTopic;
    }

    public String getCommandResultsTopic() {
        return commandResultsTopic;
    }

    public void setCommandResultsTopic(String commandResultsTopic) {
        this.commandResultsTopic = commandResultsTopic;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public void setCommandTimeoutMs(long commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }

    public int getFetchMinBytes() {
        return fetchMinBytes;
    }

    public void setFetchMinBytes(int fetchMinBytes) {
        this.fetchMinBytes = fetchMinBytes;
    }

    public int getFetchMaxWaitMs() {
        return fetchMaxWaitMs;
    }

    public void setFetchMaxWaitMs(int fetchMaxWaitMs) {
        this.fetchMaxWaitMs = fetchMaxWaitMs;
    }
}
