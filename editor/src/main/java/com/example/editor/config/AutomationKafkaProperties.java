package com.example.editor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "editor.automation.kafka")
@Getter
@Setter
public class AutomationKafkaProperties {

    private String bootstrapServers = "localhost:9092";
    private String definitionsTopic = "automation.definitions";
    private int partitions = 12;
    private short replicationFactor = 1;
}
