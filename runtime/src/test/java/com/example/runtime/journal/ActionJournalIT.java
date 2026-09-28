package com.example.runtime.journal;

import com.example.runtime.archive.ArchivePartitionManager;
import com.example.runtime.archive.ArchiveProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Журнал действий: строка не теряется даже при полной очереди и читается с фильтром. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ArchiveProperties.class, ArchivePartitionManager.class, ActionLogReader.class})
@Testcontainers
class ActionJournalIT {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired ArchiveProperties props;
    @Autowired ArchivePartitionManager partitions;
    @Autowired ActionLogReader reader;

    @Test
    void запись_при_полной_очереди_не_теряется_и_читается_с_фильтром() {
        partitions.maintain();
        props.setQueueCapacity(1);
        ActionJournal journal = new ActionJournal(jdbc, new ObjectMapper(), props);
        Instant now = Instant.now();
        ActionRecord base = new ActionRecord(now, "ivanov", 989L, ActionRecord.KIND_TAG_WRITE, null, null,
                null, List.of(Map.of("tag", "Т.V1.ST", "value", "1")), null, null);
        journal.record(base.ok());                                   // в очередь
        journal.record(base.failed("NOT_CONFIRMED"));                // очередь полна — синхронно
        journal.flushOnce();

        List<ActionRecord> rows = reader.find(989L, now.minus(1, ChronoUnit.MINUTES),
                now.plus(1, ChronoUnit.MINUTES), "ivanov", null, 0, 100);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ActionRecord::outcome).containsExactlyInAnyOrder("OK", "ERROR");
        assertThat(rows.get(0).tags()).hasSize(1);
    }
}
