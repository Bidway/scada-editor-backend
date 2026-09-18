package com.example.runtime.project;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scada-vrkf: значения свойств, записанные скриптами, переживают перезапуск runtime — и не
 * пишутся экземпляром, у которого проект уже сняли.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PropertyValueStore.class, ObjectMapper.class, PropertyValueStoreIT.Identity.class})
@Testcontainers
class PropertyValueStoreIT {

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

    @org.springframework.boot.test.context.TestConfiguration
    static class Identity {
        @org.springframework.context.annotation.Bean
        com.example.runtime.instance.InstanceIdentity instanceIdentity() {
            return new com.example.runtime.instance.InstanceIdentity("runtime-1", "http://localhost:8085");
        }
    }

    @Autowired
    PropertyValueStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void значение_переживает_запись_и_чтение_а_null_удаляет_строку() {
        assign(8501L, "runtime-1");
        store.record(8501L, 10L, "Щелочь 2%");
        store.record(8501L, 11L, 42.5);
        store.record(8501L, 12L, List.of(1, 2));
        store.flush();

        assertThat(store.load(8501L)).containsEntry(10L, "Щелочь 2%").containsEntry(11L, 42.5)
                .containsEntry(12L, List.of(1, 2));

        store.record(8501L, 11L, null);
        store.flushProject(8501L);
        assertThat(store.load(8501L)).doesNotContainKey(11L).containsKey(10L);
    }

    @Test
    void экземпляр_у_которого_проект_сняли_не_пишет() {
        assign(9000L, "runtime-2");
        store.record(9000L, 1L, true);
        store.flush();

        assertThat(store.load(9000L)).isEmpty();
    }

    private void assign(long projectId, String instanceId) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS runtime.instance_project (project_id bigint PRIMARY KEY, "
                + "instance_id varchar(64) NOT NULL, assigned_by varchar(255), assigned_at timestamptz NOT NULL)");
        jdbc.update("INSERT INTO runtime.instance_project VALUES (?, ?, 'test', now()) ON CONFLICT DO NOTHING",
                projectId, instanceId);
    }
}
