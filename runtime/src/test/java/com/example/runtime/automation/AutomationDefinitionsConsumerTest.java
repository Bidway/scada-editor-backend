package com.example.runtime.automation;

import com.example.runtime.automation.definition.ProjectDefinitions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * При старте из истории топика применяется только итог по проекту: каждая старая версия
 * перезапускала бы задачи уже поднятого проекта (scada-hssy).
 */
class AutomationDefinitionsConsumerTest {

    @Test
    void из_истории_определений_остаётся_последняя_читаемая_версия() {
        AutomationDefinitionsConsumer consumer = new AutomationDefinitionsConsumer(null,
                mock(com.example.runtime.automation.engine.AutomationEngine.class), new ObjectMapper());

        Map<Long, ProjectDefinitions> latest = consumer.latestDefinitions(List.of(
                record(0, "8501", "{\"project_id\":8501,\"version\":1}"),
                record(1, "7", "{\"project_id\":7,\"version\":1}"),
                record(2, "8501", "{\"project_id\":8501,\"version\":2}"),
                record(3, "8501", "не json"),
                record(4, "7", null)));

        assertThat(latest).containsOnlyKeys(8501L, 7L);
        assertThat(latest.get(8501L).version()).isEqualTo(2);
        assertThat(latest.get(7L)).isNull();
    }

    private static ConsumerRecord<String, String> record(long offset, String key, String value) {
        return new ConsumerRecord<>("automation.definitions", 0, offset, key, value);
    }
}
