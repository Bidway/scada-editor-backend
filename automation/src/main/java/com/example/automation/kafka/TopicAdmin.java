package com.example.automation.kafka;

import com.example.automation.config.AutomationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Свои топики сервис создаёт сам и не полагается на автосоздание: оно дало бы одну партицию,
 * и все проекты навсегда оказались бы на одном экземпляре. Неверно размеченный топик — отказ
 * старта: молча работать на одной партиции хуже, чем не подняться.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TopicAdmin {

    private static final long TIMEOUT_S = 15;

    private final AutomationProperties properties;

    public void ensureTopics() {
        AutomationProperties.Kafka kafka = properties.getKafka();
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15000))) {
            for (String topic : List.of(kafka.getDefinitionsTopic(), kafka.getStateTopic())) {
                ensure(admin, topic, kafka);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Kafka topics are not verified: " + e.getMessage(), e);
        }
    }

    private void ensure(Admin admin, String topic, AutomationProperties.Kafka kafka) throws Exception {
        if (!admin.listTopics().names().get(TIMEOUT_S, TimeUnit.SECONDS).contains(topic)) {
            try {
                admin.createTopics(List.of(new NewTopic(topic, kafka.getPartitions(), kafka.getReplicationFactor())
                                .configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT))))
                        .all().get(TIMEOUT_S, TimeUnit.SECONDS);
                log.info("Kafka topic '{}' created: {} partitions, compact", topic, kafka.getPartitions());
                return;
            } catch (ExecutionException e) {
                // Топик создал кто-то ещё (editor или второй экземпляр) — проверяем его разметку.
                if (!(e.getCause() instanceof TopicExistsException)) {
                    throw e;
                }
            }
        }
        TopicDescription description = admin.describeTopics(List.of(topic))
                .allTopicNames().get(TIMEOUT_S, TimeUnit.SECONDS).get(topic);
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        String policy = admin.describeConfigs(List.of(resource)).all().get(TIMEOUT_S, TimeUnit.SECONDS)
                .get(resource).get(TopicConfig.CLEANUP_POLICY_CONFIG).value();
        if (description.partitions().size() != kafka.getPartitions()
                || !TopicConfig.CLEANUP_POLICY_COMPACT.equals(policy)) {
            throw new IllegalStateException("Kafka topic '" + topic + "' has " + description.partitions().size()
                    + " partition(s) and cleanup.policy=" + policy + ", expected " + kafka.getPartitions()
                    + " and compact — recreate the topic");
        }
    }
}
