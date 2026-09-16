package com.example.runtime.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Кто крутит проекты этого драйвера. Драйвер — первый сегмент пути тега
 * («Барановичи-1»), то есть группа контроллеров. На этапе 1 держатель один;
 * на этапе 2 та же таблица распределяет драйверы между экземплярами.
 */
@Entity
@Table(name = "driver_lease", schema = "runtime",
        uniqueConstraints = @UniqueConstraint(name = "driver_lease_uk", columnNames = "driver"))
@Getter
@Setter
public class DriverLeaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String driver;

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "renewed_at", nullable = false)
    private Instant renewedAt;
}
