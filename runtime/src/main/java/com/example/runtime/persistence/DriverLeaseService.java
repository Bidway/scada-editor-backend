package com.example.runtime.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Кто крутит проекты драйвера. Аренда берётся после того, как экземпляр узнал свои проекты и
 * вывел из них драйверы — раньше он просто не знает, что ему нужно.
 * <p>
 * Занятый живым экземпляром драйвер означает «проекты этого драйвера не поднимаю», а не отказ
 * стартовать: отказ оставил бы оператора без диагностики вообще. На этапе 1 держатель один, и
 * аренда работает как видимый признак конфликта; на этапе 2 та же таблица распределяет драйверы
 * между экземплярами.
 */
@Service
@Slf4j
public class DriverLeaseService {

    /** Аренда считается брошенной, если её не продлевали дольше этого срока. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final DriverLeaseRepository repository;
    private final String instanceId;

    public DriverLeaseService(DriverLeaseRepository repository,
                              @Value("${runtime.instance-id:}") String configuredId) {
        this.repository = repository;
        this.instanceId = configuredId == null || configuredId.isBlank() ? hostname() : configuredId;
    }

    @Transactional
    public boolean tryAcquire(String driver) {
        Instant now = Instant.now();
        Optional<DriverLeaseEntity> existing = repository.findByDriver(driver);
        if (existing.isPresent()) {
            DriverLeaseEntity lease = existing.get();
            boolean mine = instanceId.equals(lease.getInstanceId());
            boolean stale = lease.getRenewedAt().isBefore(now.minus(STALE_AFTER));
            if (!mine && !stale) {
                log.warn("Драйвер {} уже крутит экземпляр {} — его проекты не поднимаю",
                        driver, lease.getInstanceId());
                return false;
            }
            lease.setInstanceId(instanceId);
            lease.setRenewedAt(now);
            repository.save(lease);
            return true;
        }
        DriverLeaseEntity lease = new DriverLeaseEntity();
        lease.setDriver(driver);
        lease.setInstanceId(instanceId);
        lease.setAcquiredAt(now);
        lease.setRenewedAt(now);
        try {
            repository.save(lease);
            return true;
        } catch (DataIntegrityViolationException e) {
            // Гонка за UNIQUE: другой экземпляр успел раньше между findByDriver и save.
            log.warn("Драйвер {} перехвачен другим экземпляром — его проекты не поднимаю", driver);
            return false;
        }
    }

    /** Продление: без него чужой экземпляр через STALE_AFTER сочтёт аренду брошенной. */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void renew() {
        Instant now = Instant.now();
        repository.findAll().stream()
                .filter(lease -> instanceId.equals(lease.getInstanceId()))
                .forEach(lease -> {
                    lease.setRenewedAt(now);
                    repository.save(lease);
                });
    }

    public String instanceId() {
        return instanceId;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "runtime-" + ProcessHandle.current().pid();
        }
    }
}
