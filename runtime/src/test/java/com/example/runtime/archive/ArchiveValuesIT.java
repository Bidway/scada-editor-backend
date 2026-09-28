package com.example.runtime.archive;

import com.example.runtime.archive.dto.ArchiveSeries;
import com.example.runtime.archive.dto.ArchiveValue;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** История тегов для тренда: значение на начало периода, точки периода, прореживание. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ArchiveProperties.class, ArchiveTagDictionary.class, ArchivePartitionManager.class,
        ArchiveReader.class, ArchiveQueryService.class})
@Testcontainers
class ArchiveValuesIT {

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
    @Autowired ArchiveTagDictionary dictionary;
    @Autowired ArchivePartitionManager partitions;
    @Autowired ArchiveQueryService service;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void setUp() {
        partitions.maintain();
    }

    private void point(String path, Instant ts, Double num, boolean good) {
        int id = dictionary.ensure(List.of(path)).get(path);
        jdbc.update("INSERT INTO runtime.tag_archive(tag, ts, value_num, good) VALUES (?, ?, ?, ?)",
                id, Timestamp.from(ts), num, good);
    }

    @Test
    void initial_берётся_из_прошлого_а_в_периоде_только_его_точки() {
        point("П.V1.ST", now.minus(3, ChronoUnit.HOURS), 1.0, true);     // клапан открыт давно
        point("П.V1.ST", now.minus(10, ChronoUnit.MINUTES), 0.0, true);

        ArchiveSeries s = service.values(List.of("П.V1.ST"),
                now.minus(1, ChronoUnit.HOURS), now, null).series().get(0);

        assertThat(s.initial().value()).isEqualTo(1.0);
        assertThat(s.points()).extracting(ArchiveValue::value).containsExactly(0.0);
        assertThat(s.aggregated()).isFalse();
    }

    @Test
    void прореживание_сохраняет_минимум_максимум_и_обрыв_связи() {
        Instant from = now.minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < 600; i++) {
            double v = i == 300 ? 99.0 : (i == 301 ? -5.0 : 10.0 + (i % 3));
            point("П.TE1", from.plusSeconds(i * 5L + 1), v, true);
        }
        point("П.TE1", from.plusSeconds(1502), null, false);            // обрыв связи

        ArchiveSeries s = service.values(List.of("П.TE1"), from, now, 100).series().get(0);

        assertThat(s.aggregated()).isTrue();
        assertThat(s.points().size()).isLessThanOrEqualTo(100 + 1);
        assertThat(s.points()).extracting(ArchiveValue::value).contains(99.0, -5.0);
        assertThat(s.points()).extracting(ArchiveValue::good).contains(false);
    }

    // Review Focus 4: неизвестный путь — пустая серия; период старше глубины и лишние теги — 400.
    @Test
    void неизвестный_тег_пустая_серия_а_неверный_запрос_ошибка_400() {
        ArchiveSeries s = service.values(List.of("Нет.Такого"), now.minus(1, ChronoUnit.HOURS), now, null)
                .series().get(0);
        assertThat(s.initial()).isNull();
        assertThat(s.points()).isEmpty();

        assertThatThrownBy(() -> service.values(List.of("a"), now.minus(40, ChronoUnit.DAYS), now, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.values(List.of("a"), now, now.minusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> tooMany = IntStream.range(0, 21).mapToObj(i -> "t" + i).toList();
        assertThatThrownBy(() -> service.values(tooMany, now.minusSeconds(60), now, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
