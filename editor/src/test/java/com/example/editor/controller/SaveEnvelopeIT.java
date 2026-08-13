package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Форма сохранения сцены: тело и ответ — объекты, а не голые массивы.
 * <p>
 * Метаданные едут в теле, а не заголовками: ответ обязан быть конвертом ради блока
 * {@code merged} из плана 3b, и запрос симметричен ему.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SaveEnvelopeIT extends EditorApiTestSupport {

    private String envelope(long sceneId, String saveKind) {
        return "{\"components\":[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":"
                + sceneId + "}],\"save_kind\":\"" + saveKind + "\"}";
    }

    private JsonNode postEnvelope(String body) throws Exception {
        String response = mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void saveAcceptsEnvelopeAndReturnsVersionNo() throws Exception {
        long sceneId = newScene();

        JsonNode response = postEnvelope(envelope(sceneId, "MANUAL"));

        assertThat(response.get("components")).hasSize(1);
        assertThat(response.get("components").get(0).get("id").asLong()).isPositive();
        assertThat(response.get("version_no").asInt())
                .as("номер созданной версии нужен фронту для следующего сохранения")
                .isEqualTo(1);
    }

    @Test
    void saveKindFromBodyMarksAutosave() throws Exception {
        long sceneId = newScene();

        postEnvelope(envelope(sceneId, "AUTOSAVE"));

        String versions = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/editor/scenes/" + sceneId + "/versions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(versions).get(0).get("kind").asText())
                .isEqualTo("AUTOSAVE");
    }

    @Test
    void bareArrayIsNoLongerAccepted() throws Exception {
        long sceneId = newScene();

        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":"
                                + sceneId + "}]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownSaveKindIsRejected() throws Exception {
        long sceneId = newScene();

        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId, "WHATEVER")))
                .andExpect(status().isBadRequest());
    }
}
