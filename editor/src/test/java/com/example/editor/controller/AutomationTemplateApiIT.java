package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Палитра шаблонов задач: общий CRUD без проекта, версий и истории. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutomationTemplateApiIT extends EditorApiTestSupport {

    /**
     * Круг жизни шаблона. Ловит потерю полей в jsonb-колонках и расхождение snake_case на проводе:
     * {@code example_tag} и {@code writes_variables} должны вернуться тем же, чем ушли.
     */
    @Test
    void createsReadsUpdatesAndDeletes() throws Exception {
        String name = "ПИД инкрементный " + System.nanoTime();
        JsonNode created = postTemplate(template(name, "F", "return 1;"), status().isOk());
        long id = created.get("id").asLong();
        assertThat(created.get("inputs").get(0).get("example_tag").asText())
                .isEqualTo("Барановичи-1.BN1_MCA1.FQT_F.LINE1FQT1.F");
        assertThat(created.get("timeout_ms").asInt()).isEqualTo(200);
        assertThat(created.get("writes_variables").get(0).asText()).isEqualTo("mode");

        String list = mockMvc.perform(get("/api/editor/automation-templates"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(list).findValuesAsText("name")).contains(name);

        String updated = "{\"name\":\"" + name + "\",\"category\":\"Регуляторы\","
                + "\"period_ms\":1000,\"timeout_ms\":200,\"stale_after_ms\":10000,\"run_on_stale\":false,"
                + "\"inputs\":[{\"alias\":\"F\",\"example_tag\":\"Барановичи-1.BN1_MCA1.M_V.LINE1M1.V\","
                + "\"value_type\":\"float\"}],\"outputs\":[],\"writes_variables\":[],"
                + "\"script\":\"return 2;\"}";
        mockMvc.perform(put("/api/editor/automation-templates/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(updated))
                .andExpect(status().isOk());

        String single = mockMvc.perform(get("/api/editor/automation-templates/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode saved = objectMapper.readTree(single);
        assertThat(saved.get("script").asText()).isEqualTo("return 2;");
        assertThat(saved.get("inputs").get(0).get("example_tag").asText())
                .isEqualTo("Барановичи-1.BN1_MCA1.M_V.LINE1M1.V");
        assertThat(saved.get("writes_variables")).isEmpty();

        mockMvc.perform(delete("/api/editor/automation-templates/" + id)).andExpect(status().isOk());
        mockMvc.perform(get("/api/editor/automation-templates/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/editor/automation-templates/" + id)).andExpect(status().isNotFound());
    }

    /** Отказ собирает все нарушения сразу: имя занято, псевдоним повторяется, скрипт не разбирается. */
    @Test
    void reportsAllViolationsAtOnce() throws Exception {
        String name = "Занятое имя " + System.nanoTime();
        postTemplate(template(name, "F", "return 1;"), status().isOk());

        String broken = "{\"name\":\"" + name + "\",\"period_ms\":1000,\"timeout_ms\":200,"
                + "\"stale_after_ms\":10000,\"run_on_stale\":false,"
                + "\"inputs\":[{\"alias\":\"F\",\"example_tag\":\"a\",\"value_type\":\"float\"},"
                + "{\"alias\":\"F\",\"example_tag\":\"b\",\"value_type\":\"float\"}],"
                + "\"outputs\":[],\"writes_variables\":[],\"script\":\"return (;\"}";
        JsonNode body = postTemplate(broken, status().isBadRequest());

        assertThat(body.get("error").asText()).isEqualTo("automation_invalid");
        assertThat(body.get("errors").findValuesAsText("field"))
                .containsExactlyInAnyOrder("name", "inputs", "script");
    }

    private JsonNode postTemplate(String body, org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        String response = mockMvc.perform(post("/api/editor/automation-templates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private static String template(String name, String alias, String script) {
        return "{\"name\":\"" + name + "\",\"category\":\"Регуляторы\","
                + "\"description\":\"Вход — измерение, выход 0..100 %\","
                + "\"period_ms\":1000,\"timeout_ms\":200,\"stale_after_ms\":10000,\"run_on_stale\":false,"
                + "\"inputs\":[{\"alias\":\"" + alias + "\","
                + "\"example_tag\":\"Барановичи-1.BN1_MCA1.FQT_F.LINE1FQT1.F\",\"value_type\":\"float\"}],"
                + "\"outputs\":[{\"alias\":\"U\","
                + "\"example_tag\":\"Барановичи-1.BN1_MCA1.M_V.LINE1M1.V\",\"value_type\":\"float\"}],"
                + "\"writes_variables\":[\"mode\"],\"script\":\"" + script + "\"}";
    }
}
