package com.example.runtime.project;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Догонка реестра применяет итог по каждому проекту, а не историю: до компактации в топике
 * лежат все переключения, и поочерёдное применение поднимало, гасило и снова поднимало проект
 * при каждом старте runtime (scada-3ww7).
 */
class ProjectRegistryConsumerTest {

    @Test
    void из_истории_переключений_остаётся_последнее_состояние_проекта() {
        List<ConsumerRecord<String, String>> history = List.of(
                record("8501", "{\"inOperation\":true}"),
                record("8501", null),
                record("8501", "{\"inOperation\":true}"),
                record("7", "{\"inOperation\":true}"),
                record("7", null),
                record("не-число", "{}"));

        Map<Long, Boolean> latest = ProjectRegistryConsumer.latestStates(history);

        assertThat(latest).containsExactly(Map.entry(8501L, true), Map.entry(7L, false));
    }

    @Test
    void поднятый_проект_без_записи_в_топике_гасится() {
        // Потребитель реестра пересоздан надзорным циклом, а tombstone выключенного за это время
        // проекта уже вычистила компактация: ключа в топике нет вовсе (scada-dkz1).
        Map<Long, Boolean> latest = Map.of(8501L, true);

        Map<Long, Boolean> toApply = ProjectRegistryConsumer.withVanished(latest, List.of(8501L, 42L));

        assertThat(toApply).containsExactlyInAnyOrderEntriesOf(Map.of(8501L, true, 42L, false));
    }

    private static ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("runtime.projects", 0, 0L, key, value);
    }
}
