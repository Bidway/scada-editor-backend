package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private void saveComponentsAs(String kind, String json) throws Exception {
        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .header("X-Save-Kind", kind)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
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

    private void restore(String url) throws Exception {
        mockMvc.perform(post(url).header("X-Username", USER))
                .andExpect(status().isOk());
    }

    @Test
    void restoringScene_keepsComponentAndPropertyIds() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpJson(sceneId, "10")).get(0);
        long componentId = created.get("id").asLong();
        long propertyId = propertyId(created, "Уставка");

        updateComponents(pumpUpdateJson(sceneId, componentId, "42"));

        restore("/api/editor/scenes/" + sceneId + "/restore/1");

        JsonNode component = getComponent(componentId);
        assertThat(component.get("properties").get(0).get("default_value").asText())
                .isEqualTo("10");
        assertThat(propertyId(component, "Уставка"))
                .as("id обязаны пережить восстановление: Script.id — это scriptId в ACTION с "
                        + "фронта, а сессия мониторинга берёт дерево один раз при старте")
                .isEqualTo(propertyId);
    }

    @Test
    void restoringScene_appendsNewVersionInsteadOfRewindingHistory() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpJson(sceneId, "10")).get(0);
        long componentId = created.get("id").asLong();
        updateComponents(pumpUpdateJson(sceneId, componentId, "42"));

        restore("/api/editor/scenes/" + sceneId + "/restore/1");

        JsonNode versions = getJson("/api/editor/scenes/" + sceneId + "/versions");
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).get("kind").asText()).isEqualTo("RESTORE");
        assertThat(versions.get(0).get("restored_from").asInt()).isEqualTo(1);
    }

    @Test
    void restoringScene_removesComponentsAddedAfterTheSnapshot() throws Exception {
        long sceneId = newScene();
        saveComponents(pumpJson(sceneId, "10"));
        JsonNode extra = saveComponents("[{\"name\":\"Клапан\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + "}]").get(0);
        long extraId = extra.get("id").asLong();

        restore("/api/editor/scenes/" + sceneId + "/restore/1");

        mockMvc.perform(get("/api/editor/components/" + extraId))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionList_filtersByKind() throws Exception {
        long sceneId = newScene();
        saveComponents(pumpJson(sceneId, "10"));
        saveComponentsAs("AUTOSAVE", pumpJson(sceneId, "20"));

        String body = mockMvc.perform(get("/api/editor/scenes/" + sceneId + "/versions")
                        .param("kind", "MANUAL"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode versions = objectMapper.readTree(body);
        assertThat(versions).isNotEmpty();
        for (JsonNode v : versions) {
            assertThat(v.get("kind").asText())
                    .as("фильтр по kind — ради «показать только ручные» в истории")
                    .isEqualTo("MANUAL");
        }
    }

    @Test
    void versionList_respectsLimit() throws Exception {
        long sceneId = newScene();
        saveComponents(pumpJson(sceneId, "10"));
        saveComponents(pumpJson(sceneId, "20"));
        saveComponents(pumpJson(sceneId, "30"));

        String body = mockMvc.perform(get("/api/editor/scenes/" + sceneId + "/versions")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body)).hasSize(2);
    }

    @Test
    void versionList_rejectsLimitAboveCeiling() throws Exception {
        long sceneId = newScene();
        saveComponents(pumpJson(sceneId, "10"));

        mockMvc.perform(get("/api/editor/scenes/" + sceneId + "/versions")
                        .param("limit", "501"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void versionList_filtersByPeriod() throws Exception {
        long sceneId = newScene();
        saveComponents(pumpJson(sceneId, "10"));

        String future = LocalDateTime.now().plusDays(1).toString();
        String body = mockMvc.perform(get("/api/editor/scenes/" + sceneId + "/versions")
                        .param("from", future))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body))
                .as("нижняя граница в будущем — в окно не попадает ничего")
                .isEmpty();
    }
}
