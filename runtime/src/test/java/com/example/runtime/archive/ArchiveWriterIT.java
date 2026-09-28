package com.example.runtime.archive;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ArchiveProperties.class, ArchiveTagDictionary.class, ArchivePartitionManager.class})
@Testcontainers
class ArchiveWriterIT {

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
    @Autowired ArchiveTagDictionary dictionary;
    @Autowired ArchivePartitionManager partitions;

    @Test
    void точки_из_очереди_попадают_в_архив_с_путём_через_словарь() {
        partitions.maintain();
        ArchiveRecorder recorder = new ArchiveRecorder(props, new ObjectMapper());
        ArchiveWriter writer = new ArchiveWriter(jdbc, recorder, dictionary, props);
        long now = System.currentTimeMillis();
        recorder.queue().offer(new ArchivePoint("Т.V1.ST", now, 1.0, null, true));
        recorder.queue().offer(new ArchivePoint("Т.V1.ST", now + 1, null, null, false));
        recorder.queue().offer(new ArchivePoint("Т.LS.TEXT", now, null, "Мойка", true));

        writer.flush();

        Map<String, Integer> ids = dictionary.resolve(List.of("Т.V1.ST", "Т.LS.TEXT"));
        assertThat(ids).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM runtime.tag_archive WHERE tag = ?",
                Long.class, ids.get("Т.V1.ST"))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT value_text FROM runtime.tag_archive WHERE tag = ?",
                String.class, ids.get("Т.LS.TEXT"))).isEqualTo("Мойка");
        assertThat(writer.lastError()).isNull();
        assertThat(writer.lastFlushAt()).isNotNull();
    }
}
