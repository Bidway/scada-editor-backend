package com.example.automation.kafka;

import com.example.automation.config.AutomationProperties;
import com.example.automation.definition.ProjectDefinitions;
import com.example.automation.engine.ProjectRegistry;
import com.example.automation.store.AutomationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Владение проектами: consumer group {@code automation} на {@code automation.definitions}.
 * Партиция досталась экземпляру — он исполняет её проекты.
 * <p>
 * Получение партиции: новый {@code epoch} в базе → чтение партиции с начала до конечного смещения
 * (определения своих проектов) → запуск. Новые сообщения после загрузки применяются к реестру.
 * Смещения не коммитятся: топик компактный и маленький, перечитать его дешевле, чем доверять
 * чужой позиции чтения.
 * <p>
 * Все состояния ниже трогает только поток владения — callback'и ребаланса вызываются из
 * {@code poll()} этого же потока.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OwnershipConsumer {

    private static final long RECONNECT_DELAY_MS = 5000;

    private final AutomationProperties properties;
    private final ProjectRegistry registry;
    private final AutomationStore store;
    private final OwnershipGuard guard;
    private final ObjectMapper mapper;

    private final Map<Integer, Map<Long, ProjectDefinitions>> loading = new HashMap<>();
    private final Map<Integer, Long> loadTargets = new HashMap<>();
    private final Set<Integer> needEpoch = new HashSet<>();

    private volatile boolean running;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public void start() {
        running = true;
        thread = new Thread(this::run, "automation-ownership");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            try {
                c.wakeup();
            } catch (Exception ignored) {
                // уже закрыт
            }
        }
        if (thread != null) {
            try {
                thread.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        String topic = properties.getKafka().getDefinitionsTopic();
        while (running) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(consumerProperties())) {
                consumer = c;
                c.subscribe(List.of(topic), listener(c, topic));
                while (running) {
                    ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(500));
                    guard.markPolled();
                    for (ConsumerRecord<String, String> record : records) {
                        handle(record);
                    }
                    retryEpochs();
                    activateLoaded(c, topic);
                }
            } catch (WakeupException ignored) {
                // штатная остановка
            } catch (Throwable e) {
                log.error("Ownership consumer on '{}' failed, reconnecting in {} ms: {}", topic, RECONNECT_DELAY_MS, e.toString(), e);
            } finally {
                consumer = null;
                // Штатная остановка — состояние ещё наше, сбрасываем; сбой — владение сомнительно, не пишем.
                registry.revokeAll(running);
                loading.clear();
                loadTargets.clear();
                needEpoch.clear();
            }
            if (running) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private ConsumerRebalanceListener listener(KafkaConsumer<String, String> c, String topic) {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (partitions.isEmpty()) {
                    return;
                }
                for (TopicPartition partition : partitions) {
                    loading.put(partition.partition(), new HashMap<>());
                    bumpEpoch(partition.partition());
                }
                c.seekToBeginning(partitions);
                c.endOffsets(partitions).forEach((partition, end) -> loadTargets.put(partition.partition(), end));
                log.info("Assigned partitions {} of '{}'", partitions.stream().map(TopicPartition::partition).toList(), topic);
            }

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                release(partitions, false);
            }

            @Override
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                release(partitions, true);
            }
        };
    }

    private void release(Collection<TopicPartition> partitions, boolean lost) {
        if (partitions.isEmpty()) {
            return;
        }
        List<Integer> numbers = partitions.stream().map(TopicPartition::partition).toList();
        numbers.forEach(number -> {
            loading.remove(number);
            loadTargets.remove(number);
            needEpoch.remove(number);
        });
        registry.revoke(numbers, lost);
        log.info("{} partitions {}", lost ? "Lost" : "Revoked", numbers);
    }

    private void handle(ConsumerRecord<String, String> record) {
        Long projectId = parseProjectId(record.key());
        if (projectId == null) {
            return;
        }
        ProjectDefinitions definitions = null;
        if (record.value() != null) {
            try {
                definitions = mapper.readValue(record.value(), ProjectDefinitions.class);
            } catch (Exception e) {
                // Непонятное определение не должно останавливать работающий проект.
                log.error("Project {}: unreadable definitions at offset {}, keeping previous: {}",
                        projectId, record.offset(), e.getMessage());
                return;
            }
        }
        Map<Long, ProjectDefinitions> partitionLoad = loading.get(record.partition());
        if (partitionLoad != null) {
            if (definitions == null) {
                partitionLoad.remove(projectId);
            } else {
                partitionLoad.put(projectId, definitions);
            }
        } else {
            registry.apply(record.partition(), projectId, definitions);
        }
    }

    private void bumpEpoch(int partition) {
        try {
            registry.assign(partition, store.bumpEpoch(partition, properties.getInstanceId()));
            needEpoch.remove(partition);
        } catch (Exception e) {
            // Без epoch писать в базу нельзя: проекты партиции ждут, пока база вернётся.
            needEpoch.add(partition);
            log.warn("Partition {}: epoch not acquired, projects wait for the database: {}", partition, e.getMessage());
        }
    }

    private void retryEpochs() {
        new ArrayList<>(needEpoch).forEach(this::bumpEpoch);
    }

    private void activateLoaded(KafkaConsumer<String, String> c, String topic) {
        for (Integer partition : new ArrayList<>(loading.keySet())) {
            if (needEpoch.contains(partition)) {
                continue;
            }
            Long target = loadTargets.get(partition);
            if (target == null || c.position(new TopicPartition(topic, partition)) < target) {
                continue;
            }
            registry.activate(partition, loading.remove(partition).values());
            loadTargets.remove(partition);
        }
    }

    private Properties consumerProperties() {
        AutomationProperties.Kafka kafka = properties.getKafka();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafka.getGroupId());
        props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, properties.getInstanceId());
        props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, kafka.getSessionTimeoutMs());
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, kafka.getHeartbeatIntervalMs());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    private static Long parseProjectId(String key) {
        try {
            return key == null ? null : Long.valueOf(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
