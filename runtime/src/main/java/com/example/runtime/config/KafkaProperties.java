package com.example.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Настройки Kafka. Имён топиков телеметрии и команд здесь нет: они задаются назначениями
 * экземпляру ({@code runtime.instance_topic}), а тег различается по key сообщения
 * (= путь узла — см. {@code TagValueRouter}).
 */
@Component
@ConfigurationProperties(prefix = "kafka")
public class KafkaProperties {

    private String bootstrapServers = "localhost:9092";

    /** Компактный топик активных проектов: его пишет editor, ключ — projectId. */
    private String projectsTopic = "runtime.projects";

    /** Компактный топик определений фоновых задач: пишет editor, ключ — projectId. */
    private String automationDefinitionsTopic = "automation.definitions";

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

    public String getAutomationDefinitionsTopic() {
        return automationDefinitionsTopic;
    }

    public void setAutomationDefinitionsTopic(String automationDefinitionsTopic) {
        this.automationDefinitionsTopic = automationDefinitionsTopic;
    }

    public String getProjectsTopic() {
        return projectsTopic;
    }

    public void setProjectsTopic(String projectsTopic) {
        this.projectsTopic = projectsTopic;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public void setCommandTimeoutMs(long commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
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
