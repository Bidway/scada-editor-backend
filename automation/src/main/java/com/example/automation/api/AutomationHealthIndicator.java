package com.example.automation.api;

import com.example.automation.engine.ProjectRegistry;
import com.example.automation.kafka.OwnershipGuard;
import com.example.automation.store.AutomationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * DOWN — только когда экземпляр не опрашивает Kafka: тогда он ничем не владеет и ничего не пишет.
 * Недоступная база — деталь, а не DOWN: задачи в это время работают из памяти.
 */
@Component
@RequiredArgsConstructor
public class AutomationHealthIndicator implements HealthIndicator {

    private final ProjectRegistry registry;
    private final OwnershipGuard guard;
    private final AutomationStore store;

    @Override
    public Health health() {
        boolean polling = guard.valid();
        Health.Builder builder = polling ? Health.up() : Health.down();
        builder.withDetail("kafkaPolledRecently", polling)
                .withDetail("ownedPartitions", registry.ownedPartitions())
                .withDetail("runningProjects", registry.runningProjects());
        try {
            store.ping();
            builder.withDetail("database", "UP");
        } catch (Exception e) {
            builder.withDetail("database", "DOWN: " + e.getMessage());
        }
        return builder.build();
    }
}
