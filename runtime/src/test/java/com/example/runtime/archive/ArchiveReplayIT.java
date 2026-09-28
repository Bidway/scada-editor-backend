package com.example.runtime.archive;

import com.example.runtime.archive.dto.ReplayChange;
import com.example.runtime.archive.dto.ReplayRequest;
import com.example.runtime.archive.dto.ReplayResponse;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Воспроизведение сцены: состояние на начало периода и изменения страницами по курсору. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ArchiveProperties.class, ArchiveTagDictionary.class, ArchivePartitionManager.class,
        ArchiveReader.class, ArchiveQueryService.class})
@Testcontainers
class ArchiveReplayIT {

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
    @Autowired ArchiveQueryService service;

    private void point(String path, Instant ts, double num) {
        int id = dictionary.ensure(List.of(path)).get(path);
        jdbc.update("INSERT INTO runtime.tag_archive(tag, ts, value_num, good) VALUES (?, ?, ?, true)",
                id, Timestamp.from(ts), num);
    }

    // Review Focus 5: стык страниц посреди строк с одинаковым ts у разных тегов.
    @Test
    void страницы_отдают_все_изменения_без_пропусков_и_повторов() {
        // Задаётся руками, а не свойством: @JdbcTest не гарантирует привязку @ConfigurationProperties.
        props.setReplayPageSize(3);
        partitions.maintain();
        Instant t0 = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        point("R.A", t0.minusSeconds(600), 7.0);                         // до периода — только в initial
        Instant same = t0.plusSeconds(10);
        for (String tag : List.of("R.A", "R.B", "R.C", "R.D", "R.E")) {
            point(tag, same, 1.0);                                      // пять строк с одним ts
        }
        point("R.A", t0.plusSeconds(20), 0.0);

        List<String> tags = List.of("R.A", "R.B", "R.C", "R.D", "R.E", "R.НЕТ");
        List<ReplayChange> all = new ArrayList<>();
        ReplayResponse page = service.replay(new ReplayRequest(tags, t0, t0.plusSeconds(60), null));
        assertThat(page.initial().get("R.A").value()).isEqualTo(7.0);
        assertThat(page.initial()).containsEntry("R.НЕТ", null);
        all.addAll(page.changes());
        while (page.next() != null) {
            page = service.replay(new ReplayRequest(tags, t0, t0.plusSeconds(60), page.next()));
            assertThat(page.initial()).isNull();
            all.addAll(page.changes());
        }

        assertThat(all).hasSize(6);
        assertThat(all).extracting(c -> c.tag() + "@" + c.ts()).doesNotHaveDuplicates();
        assertThat(all.get(5).tag()).isEqualTo("R.A");
        assertThat(all.get(5).value()).isEqualTo(0.0);
    }
}
