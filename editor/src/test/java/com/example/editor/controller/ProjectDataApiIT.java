package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Набор таблиц проекта: сохраняется целиком, версионируется, отдаётся по алфавиту заголовков. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectDataApiIT extends EditorApiTestSupport {

    private static final String SOLUTIONS = "{\"name\":\"solutions\",\"title\":\"Растворы\","
            + "\"columns\":[{\"name\":\"density\",\"value_type\":\"float\",\"required\":true}],"
            + "\"rows\":[{\"key\":\"ALK\",\"values\":{\"density\":1.32}}]}";
    private static final String ALARMS = "{\"name\":\"alarms\",\"title\":\"аварии\","
            + "\"columns\":[{\"name\":\"text\",\"value_type\":\"string\"}],"
            + "\"rows\":[{\"key\":\"A1\",\"values\":{\"text\":\"Нет расхода\"}}]}";

    /**
     * Весь путь документа: порядок по заголовку без учёта регистра («аварии» раньше «Растворы»,
     * хотя строчная «а» в кодовой таблице дальше заглавной «Р»), удаление таблицы по отсутствию
     * в теле и отсутствие новой версии при пересохранении без изменений.
     */
    @Test
    void savesWholeSetWithVersions() throws Exception {
        long projectId = createProject("data-" + System.nanoTime());

        JsonNode first = saveData(projectId, null, SOLUTIONS + "," + ALARMS);
        assertThat(first.get("version").asInt()).isEqualTo(1);
        assertThat(first.get("tables").get(0).get("name").asText()).isEqualTo("alarms");
        assertThat(first.get("tables").get(1).get("rows").get(0).get("values").get("density").asDouble())
                .isEqualTo(1.32);

        JsonNode second = saveData(projectId, 1, SOLUTIONS);
        assertThat(second.get("version").asInt()).isEqualTo(2);
        assertThat(second.get("tables")).hasSize(1);

        JsonNode same = saveData(projectId, 2, SOLUTIONS);
        assertThat(same.get("version").asInt()).isEqualTo(2);
        assertThat(versionsOf(projectId, "data")).hasSize(2);
    }

    private JsonNode saveData(long projectId, Integer basedOnVersion, String tablesJson) throws Exception {
        String body = "{" + (basedOnVersion == null ? "" : "\"based_on_version\":" + basedOnVersion + ",")
                + "\"tables\":[" + tablesJson + "]}";
        String response = mockMvc.perform(put("/api/editor/projects/" + projectId + "/data")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }
}
