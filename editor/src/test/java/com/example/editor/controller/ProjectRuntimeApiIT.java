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
 * Флаг «проект в эксплуатации» и prod-выпуск. Без prod проект не включается: runtime нечего
 * крутить, живое дерево он не читает.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectRuntimeApiIT extends EditorApiTestSupport {

    @Test
    void без_prod_выпуска_в_эксплуатацию_не_вводится() throws Exception {
        long projectId = createProject("Без выпуска");

        mockMvc.perform(setInOperation(projectId, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_prod_release"));
    }

    @Test
    void выпуск_назначается_prod_и_проект_включается() throws Exception {
        long projectId = createProject("С выпуском");
        mockMvc.perform(post("/api/editor/projects/" + projectId + "/versions")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER)
                        .content("{\"comment\":\"первый\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(setProd(projectId, 7)).andExpect(status().isNotFound());
        mockMvc.perform(setProd(projectId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prodVersionNo").value(1))
                .andExpect(jsonPath("$.inOperation").value(false));

        mockMvc.perform(setInOperation(projectId, true)).andExpect(status().isOk());
        mockMvc.perform(get("/api/editor/projects/" + projectId + "/runtime"))
                .andExpect(jsonPath("$.inOperation").value(true))
                .andExpect(jsonPath("$.prodVersionNo").value(1));
    }

    private RequestBuilder setInOperation(long id, boolean on) {
        return put("/api/editor/projects/" + id + "/runtime")
                .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER)
                .content("{\"inOperation\":" + on + "}");
    }

    private RequestBuilder setProd(long id, int versionNo) {
        return put("/api/editor/projects/" + id + "/runtime/prod")
                .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER)
                .content("{\"versionNo\":" + versionNo + "}");
    }
}
