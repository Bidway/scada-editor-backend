package com.example.editor.service.automation;

import com.example.editor.config.AutomationKafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Топик {@code automation.definitions} создаётся явно, до первой публикации. На стенде включено
 * автосоздание топиков: первый же {@code send} создал бы топик с одной партицией и без компакции,
 * и сервис {@code automation} на нём не стартует. Существующий топик с неверной разметкой —
 * публикация не идёт, в лог — причина.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutomationTopicInitializer {

    private final AutomationKafkaProperties properties;

    private volatile boolean ready;

    /** {@code true} — топик есть и размечен верно, публиковать можно. Успех запоминается. */
    public boolean ensureTopic() {
        if (ready) {
            return true;
        }
        synchronized (this) {
            if (ready) {
                return true;
            }
            String topic = properties.getDefinitionsTopic();
            try (Admin admin = Admin.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers(),
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000))) {
                if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(topic)) {
                    NewTopic newTopic = new NewTopic(topic, properties.getPartitions(), properties.getReplicationFactor())
                            .configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT));
                    admin.createTopics(List.of(newTopic)).all().get(10, TimeUnit.SECONDS);
                    log.info("Kafka topic '{}' created: {} partitions, compact", topic, properties.getPartitions());
                    ready = true;
                    return true;
                }
                TopicDescription description = admin.describeTopics(List.of(topic))
                        .allTopicNames().get(10, TimeUnit.SECONDS).get(topic);
                ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
                String policy = admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS)
                        .get(resource).get(TopicConfig.CLEANUP_POLICY_CONFIG).value();
                if (description.partitions().size() != properties.getPartitions()
                        || !TopicConfig.CLEANUP_POLICY_COMPACT.equals(policy)) {
                    log.error("Kafka topic '{}' has {} partition(s) and cleanup.policy={}, expected {} and compact: "
                                    + "automation definitions are NOT published until the topic is recreated",
                            topic, description.partitions().size(), policy, properties.getPartitions());
                    return false;
                }
                ready = true;
                return true;
            } catch (Exception e) {
                log.warn("Kafka topic '{}' is not verified yet: {}", topic, e.getMessage());
                return false;
            }
        }
    }
}
