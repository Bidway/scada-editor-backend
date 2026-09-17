package com.example.runtime.instance;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Регистрирует экземпляр при старте. Живой экземпляр с тем же именем и другим адресом — отказ
 * стартовать: два процесса с одним именем читали бы одни и те же назначенные топики. Перезапуск того
 * же экземпляра на том же адресе проходит.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceRegistrar {

    /** Экземпляр отмечается раз в 5 с (AssignmentApplier); 30 с тишины — точно не жив. */
    private static final Duration ALIVE = Duration.ofSeconds(30);

    private final InstanceIdentity identity;
    private final InstanceRepository repository;

    @PostConstruct
    @Transactional
    public void register() {
        Instant now = Instant.now();
        InstanceEntity row = repository.findById(identity.instanceId()).orElseGet(InstanceEntity::new);
        boolean alive = row.getLastSeenAt() != null && row.getLastSeenAt().isAfter(now.minus(ALIVE));
        if (alive && !identity.baseUrl().equals(row.getBaseUrl())) {
            throw new IllegalStateException("Экземпляр runtime с именем " + identity.instanceId()
                    + " уже работает по адресу " + row.getBaseUrl() + " — второй с тем же именем не стартует");
        }
        row.setInstanceId(identity.instanceId());
        row.setBaseUrl(identity.baseUrl());
        row.setLastSeenAt(now);
        repository.save(row);
        log.info("Экземпляр runtime {} зарегистрирован по адресу {}", identity.instanceId(), identity.baseUrl());
    }

    @Transactional
    public void markSeen() {
        repository.findById(identity.instanceId()).ifPresent(row -> {
            row.setLastSeenAt(Instant.now());
            repository.save(row);
        });
    }
}
