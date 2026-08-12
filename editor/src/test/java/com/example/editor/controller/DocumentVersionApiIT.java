package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Чтение истории документа. Содержимое версии отдаётся в той же форме, что и обычный GET
 * документа: показывать старую версию фронт должен тем же кодом, которым рисует текущую.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentVersionApiIT extends EditorApiTestSupport {

    private String pumpJson(long sceneId, String setpoint) {
        return "[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\",\"default_value\":\"" + setpoint + "\"}]}]";
    }

    private String pumpUpdateJson(long sceneId, long componentId, String setpoint) {
        return "[{\"id\":" + componentId + ",\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ",\"properties\":[{\"name\":\"Уставка\","
                + "\"value_type\":\"double\",\"property_type\":\"Тег\","
                + "\"default_value\":\"" + setpoint + "\"}]}]";
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void versionsOfScene_areListedNewestFirst() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpJson(sceneId, "10")).get(0);
        long componentId = created.get("id").asLong();
        updateComponents(pumpUpdateJson(sceneId, componentId, "42"));

        JsonNode versions = getJson("/api/editor/scenes/" + sceneId + "/versions");

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version_no").asInt()).isEqualTo(2);
        assertThat(versions.get(0).get("kind").asText()).isEqualTo("MANUAL");
        assertThat(versions.get(0).get("user_name").asText()).isEqualTo(USER);
    }

    @Test
    void versionContent_hasTheShapeOfDocumentGet() throws Exception {
        long sceneId = newScene();
        saveComponents(pumpJson(sceneId, "10"));

        JsonNode content = getJson("/api/editor/scenes/" + sceneId + "/versions/1");

        assertThat(content.get("id").asLong()).isEqualTo(sceneId);
        assertThat(content.get("children").get(0).get("properties").get(0)
                .get("default_value").asText()).isEqualTo("10");
    }

    @Test
    void stateAtTime_returnsVersionInForceThen() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpJson(sceneId, "10")).get(0);
        long componentId = created.get("id").asLong();
        String between = java.time.LocalDateTime.now().toString();
        Thread.sleep(20);
        updateComponents(pumpUpdateJson(sceneId, componentId, "42"));

        JsonNode content = getJson("/api/editor/scenes/" + sceneId + "/at?time=" + between);

        assertThat(content.get("children").get(0).get("properties").get(0)
                .get("default_value").asText())
                .as("на этот момент действовала первая версия")
                .isEqualTo("10");
    }

    @Test
    void unknownDocumentType_isRejected() throws Exception {
        mockMvc.perform(get("/api/editor/recipes/1/versions"))
                .andExpect(status().isBadRequest());
    }
}
