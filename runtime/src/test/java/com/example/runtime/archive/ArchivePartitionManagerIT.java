package com.example.runtime.archive;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Секции архива по суткам: создаются заранее, удаляются целиком по глубине хранения. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ArchiveProperties.class)
@Testcontainers
// Без транзакции теста: менеджер работает своим соединением и должен видеть строки теста.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ArchivePartitionManagerIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:automation-schema.sql");
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Minsk");

    @Autowired JdbcTemplate jdbc;
    @Autowired ArchiveProperties props;

    private ArchivePartitionManager managerAt(String isoInstant) {
        return new ArchivePartitionManager(jdbc, props,
                Clock.fixed(Instant.parse(isoInstant), ZONE));
    }

    @Test
    void создаёт_секции_на_сегодня_и_завтра_и_удаляет_старше_глубины() {
        // Секция 40-дневной давности — как будто осталась от прошлого месяца.
        managerAt("2026-08-19T10:00:00Z").maintain();
        assertThat(managerAt("2026-08-19T10:00:00Z").partitions(ArchivePartitionManager.TAG_ARCHIVE))
                .contains("20260819", "20260820");

        ArchivePartitionManager now = managerAt("2026-09-28T10:00:00Z");
        now.maintain();

        assertThat(now.partitions(ArchivePartitionManager.TAG_ARCHIVE))
                .contains("20260928", "20260929")
                .doesNotContain("20260819", "20260820");
        assertThat(now.partitions(ArchivePartitionManager.ACTION_LOG))
                .contains("20260928", "20260929");
    }

    /**
     * Ревью, Important-1: строка в DEFAULT за сутки без секции раньше навсегда блокировала
     * CREATE … PARTITION OF на этот диапазон (Postgres: «updated partition constraint for default
     * partition would be violated») — и вместе с ним всё обслуживание одной транзакцией.
     */
    @Test
    void секция_создаётся_даже_если_в_default_уже_есть_строки_её_суток() {
        managerAt("2026-10-10T10:00:00Z").maintain();          // секции 10 и 11 октября
        jdbc.update("INSERT INTO runtime.tag_archive(tag, ts, value_num, good) VALUES (7, '2026-10-12T09:00:00Z', 5, true)");
        jdbc.update("INSERT INTO runtime.action_log(ts, kind, outcome) VALUES ('2026-10-12T09:00:00Z', 'ACTION', 'OK')");

        ArchivePartitionManager next = managerAt("2026-10-11T10:00:00Z");
        next.maintain();                                       // нужна секция 12 октября

        assertThat(next.partitions(ArchivePartitionManager.TAG_ARCHIVE)).contains("20261012");
        assertThat(next.partitions(ArchivePartitionManager.ACTION_LOG)).contains("20261012");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM runtime.tag_archive_20261012 WHERE tag = 7", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM runtime.tag_archive_default WHERE tag = 7", Long.class)).isZero();
    }

    @Test
    void строка_без_своей_секции_уходит_в_default_и_видна_счётчиком() {
        ArchivePartitionManager now = managerAt("2026-09-28T10:00:00Z");
        now.maintain();
        jdbc.update("INSERT INTO runtime.tag_archive(tag, ts, value_num, good) VALUES (1, '2031-01-01T00:00:00Z', 1, true)");

        assertThat(now.defaultRows(ArchivePartitionManager.TAG_ARCHIVE)).isEqualTo(1);
    }
}
