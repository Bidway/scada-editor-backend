package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ошибка клиента не должна выглядеть как падение сервера: общий обработчик исключений отдавал
 * 500 и на опечатку в пути, и на чужой метод (scada-i2n).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnknownPathIT extends EditorApiTestSupport {

    @Test
    void unknownPath_answers404() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    /** scada-o8d5: без X-Username изменяющий эндпоинт отдавал 500. */
    @Test
    void missingUsernameHeader_answers400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/editor/components/scene")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"s\",\"project_id\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethod_answers405() throws Exception {
        mockMvc.perform(patch("/api/editor/components")).andExpect(status().isMethodNotAllowed());
    }
}
