package com.example.runtime.automation.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Схема automation поднимается скриптом runtime поверх старой базы сервиса automation:
 * колонки epoch и partition_epoch уходят, память задачи и опоздание такта пишутся.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AutomationStore.class, ObjectMapper.class})
@Testcontainers
class AutomationStoreIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            // Старая схема сервиса automation — скрипт runtime обязан её доработать, а не упасть.
            .withInitScript("automation-schema-legacy.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:automation-schema.sql");
    }

    @Autowired
    AutomationStore store;

    @Test
    void память_задачи_и_опоздание_такта_пишутся_без_epoch() {
        store.saveCheckpoints(List.of(new AutomationStore.CheckpointRow(8501L, 1L, "hash", Map.of("i", 2.5))));
        store.saveStatuses(List.of(new AutomationStore.StatusRow(8501L, 1L, "ПИД", "RUNNING", 1000L, 3L,
                null, 0L, "runtime-1", 12L)));

        assertThat(store.loadCheckpoint(8501L, 1L)).get()
                .extracting(AutomationStore.CheckpointRow::state).isEqualTo(Map.of("i", 2.5));
        assertThat(store.statuses(8501L)).singleElement()
                .extracting(AutomationStore.StatusRow::lastLagMs).isEqualTo(12L);
    }
}
