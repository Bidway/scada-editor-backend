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
}
