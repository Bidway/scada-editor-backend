package com.example.channel.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InfraSmokeIT extends ChannelApiTestSupport {

    @Test
    void createsNodeAndWritesLog() throws Exception {
        JsonNode response = createNode("SMOKE-1", 1L);

        assertThat(response.has("batchId")).isTrue();
        assertThat(logIdsDesc()).isNotEmpty();
    }
}
