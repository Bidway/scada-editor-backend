package com.example.editor.service;

import com.example.editor.config.AutomationKafkaProperties;
import com.example.editor.repository.ProjectRuntimeFlagRepository;
import com.example.editor.service.automation.AutomationTopicInitializer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * Публикует проекты «в эксплуатации» компактным топиком, ключ — projectId.
 * Выключенный проект публикуется tombstone-ом (value = null), чтобы компактация его выбросила.
 * <p>
 * Outbox здесь сознательно не заводится: в топик едёт один булев флаг на проект, и полная
 * пересинхронизация раз в минуту ({@link #resync()}) даёт ту же гарантию доставки, что и
 * очередь с релеем, без второй таблицы.
 */
@Service
@Slf4j
public class RuntimeProjectsPublisher {

    private final ProjectRuntimeFlagRepository repository;
    private final AutomationTopicInitializer topicInitializer;
    private final AutomationKafkaProperties kafkaProperties;
    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final boolean enabled;

    // producer собирается так же, как в AutomationDefinitionsPublisher: тот же набор свойств
    // и тот же жизненный цикл (создаётся с бином, закрывается перед уничтожением).
    public RuntimeProjectsPublisher(ProjectRuntimeFlagRepository repository,
                                     AutomationTopicInitializer topicInitializer,
                                     AutomationKafkaProperties kafkaProperties,
                                     @Value("${editor.runtime-projects.topic}") String topic,
                                     @Value("${editor.runtime-projects.publish-enabled:true}") boolean enabled) {
        this.repository = repository;
        this.topicInitializer = topicInitializer;
        this.kafkaProperties = kafkaProperties;
        this.topic = topic;
        this.enabled = enabled;

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        this.producer = new KafkaProducer<>(props);
    }

    @PreDestroy
    void shutdown() {
        if (producer != null) {
            producer.close();
        }
    }

    public void publish(Long projectId, boolean inOperation) {
        // Выключается в тестовом профиле: тесты editor поднимают контекст с настоящим адресом
        // брокера, и прогон ProjectRuntimeApiIT включал проект 8501 на стенде (scada-ocqj).
        if (!enabled) {
            return;
        }
        // Топик размечается тем же способом, что automation.definitions (AutomationTopicInitializer):
        // на стенде включено автосоздание топиков, и первый же send без явной разметки создал бы
        // runtime.projects с одной партицией и без compact.
        if (!topicInitializer.ensureTopic(topic, kafkaProperties.getPartitions(), kafkaProperties.getReplicationFactor())) {
            log.warn("Топик {} ещё не размечен, проект {} не опубликован — досошлётся при пересинхронизации",
                    topic, projectId);
            return;
        }
        String value = inOperation ? "{\"projectId\":" + projectId + ",\"inOperation\":true}" : null;
        producer.send(new ProducerRecord<>(topic, String.valueOf(projectId), value), (meta, e) -> {
            if (e != null) {
                log.warn("Не удалось опубликовать проект {} в {}: {}", projectId, topic, e.toString());
            }
        });
    }

    /** Полная пересинхронизация: страховка вместо outbox. */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${editor.runtime-projects.resync-interval-ms:60000}")
    public void resync() {
        repository.findAll().forEach(f -> publish(f.getProjectId(), f.isInOperation()));
    }
}
