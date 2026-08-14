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

    /**
     * {@code RESTORE} — kind, который ставит только сервер при восстановлении версии, а не
     * значение, доступное клиенту через конверт. Иначе {@code save_kind=RESTORE} стал бы
     * лазейкой в обход проверки {@code based_on_version}: у восстановления её нет по смыслу
     * (оно всегда дописывает версию поверх текущей), и {@code ComponentServiceImpl} эту
     * проверку для него не делает.
     */
    @Test
    void restoreSaveKindIsRejected() throws Exception {
        long sceneId = newScene();

        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId, "RESTORE")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savingWithoutBaseVersion_whenVersionsExist_is400() throws Exception {
        long sceneId = newScene();
        postEnvelope(envelope(sceneId, "MANUAL"));

        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId, "MANUAL")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void firstSaveWithoutBaseVersion_isAllowed() throws Exception {
        long sceneId = newScene();

        JsonNode response = postEnvelope(envelope(sceneId, "MANUAL"));

        assertThat(response.get("version_no").asInt())
                .as("версий ещё не было — базе неоткуда взяться")
                .isEqualTo(1);
    }

    @Test
    void staleBaseVersion_is409WithBothNumbers() throws Exception {
        long sceneId = newScene();
        postEnvelope(envelope(sceneId, "MANUAL"));
        JsonNode second = postEnvelope("{\"components\":[{\"name\":\"Клапан\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + "}],\"based_on_version\":1,"
                + "\"save_kind\":\"MANUAL\"}");
        int current = second.get("version_no").asInt();

        String body = mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"name\":\"Задвижка\",\"type\":\"valve\","
                                + "\"parent_id\":" + sceneId + "}],\"based_on_version\":1,"
                                + "\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        JsonNode error = objectMapper.readTree(body);
        assertThat(error.get("error").asText()).isEqualTo("version_mismatch");
        assertThat(error.get("base_version").asInt()).isEqualTo(1);
        assertThat(error.get("current_version").asInt()).isEqualTo(current);
    }

    @Test
    void matchingBaseVersion_passes() throws Exception {
        long sceneId = newScene();
        JsonNode first = postEnvelope(envelope(sceneId, "MANUAL"));
        int base = first.get("version_no").asInt();

        JsonNode second = postEnvelope("{\"components\":[{\"name\":\"Клапан\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + "}],\"based_on_version\":" + base + ","
                + "\"save_kind\":\"MANUAL\"}");

        assertThat(second.get("version_no").asInt()).isGreaterThan(base);
    }
}
