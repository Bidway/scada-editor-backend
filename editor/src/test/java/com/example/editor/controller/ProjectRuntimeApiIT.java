package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Флаг «проект в эксплуатации»: по умолчанию выключен, включается и выключается PUT-ом. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectRuntimeApiIT extends EditorApiTestSupport {

    @Test
    void включает_и_выключает_проект() throws Exception {
        mockMvc.perform(get("/api/editor/projects/8501/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inOperation").value(false));

        mockMvc.perform(put("/api/editor/projects/8501/runtime")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Username", "tester")
                        .content("{\"inOperation\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inOperation").value(true));

        mockMvc.perform(get("/api/editor/projects/8501/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inOperation").value(true));
    }
}
