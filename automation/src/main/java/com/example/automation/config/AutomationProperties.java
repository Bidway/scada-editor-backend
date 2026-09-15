package com.example.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "automation")
@Getter
@Setter
public class AutomationProperties {

    private String instanceId = "automation-local";
    /** Откуда брать таблицы данных проекта: {@code GET /api/editor/projects/{id}/data}. */
    private String editorBaseUrl = "http://localhost:8083";
    private Kafka kafka = new Kafka();
    private Engine engine = new Engine();

    @Getter
    @Setter
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String definitionsTopic = "automation.definitions";
        private String stateTopic = "automation.state";
        private String tagsTopic = "scada.tags";
        private String commandsTopic = "scada-commands";
        private String commandResultsTopic = "scada-command-results";
        private String groupId = "automation";
        private int partitions = 12;
        private short replicationFactor = 1;
        private int sessionTimeoutMs = 10000;
        private int heartbeatIntervalMs = 3000;
        private long commandTimeoutMs = 5000;
    }

    @Getter
    @Setter
    public static class Engine {
        private int workerThreads = 4;
        private int contextPoolSize = 4;
        private long checkpointFlushMs = 1000;
        private long statusRefreshMs = 10000;
        /** Первая пауза повтора загрузки данных проекта; удваивается до {@link #dataRetryMaxMs}. */
        private long dataRetryMinMs = 5000;
        private long dataRetryMaxMs = 60000;
    }
}
