package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code image} проекта (закреплённые схемы) лежит в project_runtime рядом с флагом эксплуатации:
 * запись не должна задевать флаг и prod, а список проектов — отдавать поле всегда.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectImageApiIT extends EditorApiTestSupport {

    @Test
    void image_попадает_в_список_проектов_и_не_трогает_эксплуатацию() throws Exception {
        long pinned = createProject("С закреплениями");
        long plain = createProject("Без закреплений");
        mockMvc.perform(post("/api/editor/projects/" + pinned + "/versions")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER)
                        .content("{\"comment\":\"первый\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/editor/projects/" + pinned + "/runtime/prod")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER)
                        .content("{\"versionNo\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/editor/projects/" + pinned + "/runtime")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER)
                        .content("{\"inOperation\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(putImage(pinned, "{\"image\":{\"pinnedScenes\":{\"v\":1,\"ids\":[12,7]}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image.pinnedScenes.ids[1]").value(7));

        mockMvc.perform(get("/api/editor/projects/" + pinned + "/runtime"))
                .andExpect(jsonPath("$.inOperation").value(true))
                .andExpect(jsonPath("$.prodVersionNo").value(1));
        mockMvc.perform(get("/api/editor/components/projects"))
                .andExpect(jsonPath("$[?(@.id == " + pinned + ")].image.pinnedScenes.v").value(1))
                .andExpect(jsonPath("$[?(@.id == " + plain + ")].image").value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void не_объект_400_нет_проекта_404() throws Exception {
        long projectId = createProject("Проверки");

        mockMvc.perform(putImage(projectId, "{\"image\":[1,2]}")).andExpect(status().isBadRequest());
        mockMvc.perform(putImage(projectId, "{}")).andExpect(status().isBadRequest());
        mockMvc.perform(putImage(Long.MAX_VALUE, "{\"image\":{}}")).andExpect(status().isNotFound());
    }

    private RequestBuilder putImage(long id, String body) {
        return put("/api/editor/projects/" + id + "/image")
                .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER)
                .content(body);
    }
}
