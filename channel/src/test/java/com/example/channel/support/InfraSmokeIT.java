package com.example.channel.support;

import com.example.channel.config.command.CommandLog;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InfraSmokeIT extends ChannelApiTestSupport {

    @Test
    void createsNodeAndWritesLog() throws Exception {
        List<Long> before = logIdsDesc();

        JsonNode response = createNode("SMOKE-1", 1L);

        List<Long> after = logIdsDesc();
        assertThat(after.size())
                .as("вызов должен был добавить хотя бы одну запись в command_log")
                .isGreaterThan(before.size());
        assertThat(before).doesNotContain(after.get(0));

        UUID batchId = UUID.fromString(response.get("batchId").asText());
        CommandLog newest = commandLogRepository.findById(after.get(0)).orElseThrow();
        assertThat(newest.getBatchId())
                .as("batchId из ответа должен указывать на реально записанный лог")
                .isEqualTo(batchId);
    }
}
