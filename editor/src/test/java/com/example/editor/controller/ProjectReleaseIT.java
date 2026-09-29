package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Выпуск проекта — версия документа PROJECT: целое дерево, дедуп по хешу, без восстановления. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectReleaseIT extends EditorApiTestSupport {

    @Test
    void выпуск_снимает_дерево_а_повторный_без_правок_не_пишет_версию() throws Exception {
        long projectId = createProject("Выпуски");
        long sceneId = createScene("Главная", projectId);

        mockMvc.perform(release(projectId, "первый"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version_no").value(1))
                .andExpect(jsonPath("$.comment").value("первый"))
                .andExpect(jsonPath("$.unchanged").value(false));
        mockMvc.perform(release(projectId, "второй"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version_no").value(1))
                .andExpect(jsonPath("$.comment").value("первый"))
                .andExpect(jsonPath("$.unchanged").value(true));

        JsonNode tree = versionContent(projectId, "projects", 1);
        assertThat(tree.path("id").asLong()).isEqualTo(projectId);
        assertThat(tree.path("children").findValuesAsText("id")).contains(String.valueOf(sceneId));
        assertThat(versionsOf(projectId, "projects").get(0).path("comment").asText()).isEqualTo("первый");
    }

    @Test
    void выпуск_не_проекта_и_восстановление_выпуска_отклоняются() throws Exception {
        long projectId = createProject("Выпуски-2");
        long sceneId = createScene("Главная", projectId);

        mockMvc.perform(release(sceneId, "сцена")).andExpect(status().isBadRequest());

        mockMvc.perform(release(projectId, "первый")).andExpect(status().isOk());
        mockMvc.perform(post("/api/editor/projects/" + projectId + "/restore/1").header("X-Username", USER))
                .andExpect(status().isBadRequest());
    }

    private RequestBuilder release(long id, String comment) {
        return post("/api/editor/projects/" + id + "/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Username", USER)
                .content("{\"comment\":\"" + comment + "\"}");
    }
}
