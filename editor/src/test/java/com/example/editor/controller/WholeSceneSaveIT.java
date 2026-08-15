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

/**
 * {@code PUT} несёт сцену целиком: отсутствие компонента в теле означает его удаление.
 * <p>
 * Без этого слияние не может отличить «я удалил» от «я не прислал», и половина конфликтов из
 * контракта (§2) невыразима. Цена — {@code scene_id} в конверте: по пустому массиву иначе не
 * понять, чьи это дети, а «стереть не ту сцену» — самая дорогая ошибка из возможных здесь.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WholeSceneSaveIT extends EditorApiTestSupport {

    private String twoComponents(long sceneId) {
        return "[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + "},"
                + "{\"name\":\"Клапан\",\"type\":\"valve\",\"parent_id\":" + sceneId + "}]";
    }

    @Test
    void componentMissingFromThePut_isDeleted() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(twoComponents(sceneId));
        long pumpId = created.get(0).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        updateScene(sceneId, "[{\"id\":" + pumpId + ",\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + "}]", base);

        assertThat(getComponent(sceneId).get("children"))
                .as("прислали сцену целиком — чего в ней нет, того нет и в базе")
                .hasSize(1);
    }

    @Test
    void putWithoutSceneId_isRejected() throws Exception {
        long sceneId = newScene();
        saveComponents(twoComponents(sceneId));
        Integer base = currentVersion(sceneId, "scenes");

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[],\"based_on_version\":" + base
                                + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyPutWithSceneId_clearsTheScene() throws Exception {
        long sceneId = newScene();
        saveComponents(twoComponents(sceneId));
        Integer base = currentVersion(sceneId, "scenes");

        updateScene(sceneId, "[]", base);

        assertThat(getComponent(sceneId).get("children")).isEmpty();
    }

    /**
     * C1 (критично, найдено ревью 5285aa4): «сначала пишем, потом чистим» удаляло компонент без
     * id той же транзакцией, где он был создан. IDENTITY выдаёт id на вставке, но в {@code keep}
     * чистки попадают только явные id из присланных dto — свежий id в него не входит, и чистка
     * после записи считала только что созданную строку чужой и удаляла её через orphanRemoval,
     * а клиенту при этом возвращался 200 с id уже несуществующей записи. Порядок исправлен на
     * «сначала чистка присланного, потом запись» — как в {@code SceneDocumentSource.restore}.
     */
    @Test
    void newComponentWithoutId_survivesAlongsideExisting() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(twoComponents(sceneId));
        long pumpId = created.get(0).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateScene(sceneId,
                "[{\"id\":" + pumpId + ",\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":"
                        + sceneId + "},"
                        + "{\"name\":\"Новый\",\"type\":\"valve\",\"parent_id\":" + sceneId + "}]",
                base);

        long newId = -1L;
        for (JsonNode c : updated) {
            if (c.get("id").asLong() != pumpId) {
                newId = c.get("id").asLong();
            }
        }
        assertThat(newId).as("PUT обязан вернуть id нового компонента").isNotEqualTo(-1L);

        assertThat(getComponent(sceneId).get("children"))
                .as("новый компонент без id не должен погибнуть в той же транзакции, где создан")
                .hasSize(2);
        assertThat(getComponent(newId).get("id").asLong())
                .as("id, вернувшийся клиенту в ответе, обязан реально существовать в базе")
                .isEqualTo(newId);
    }

    /**
     * I2 (найдено ревью 5285aa4): {@code scene_id} обязан адресовать именно сцену. Раньше он
     * подавался в чистку без проверки типа — id проекта прошёл бы точно так же, и по пустому
     * {@code components} чистка сочла бы «не присланными» все сцены под этим проектом и стёрла
     * бы их. Тип проверяется {@code requireScene} до любой записи.
     */
    @Test
    void sceneIdThatIsNotAScene_isRejectedWithoutTouchingChildren() throws Exception {
        long projectId = createProject("proj-" + System.nanoTime());
        long sceneId = createScene("scene-" + System.nanoTime(), projectId);
        saveComponents(twoComponents(sceneId));

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[],\"scene_id\":" + projectId
                                + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());

        assertThat(getComponent(sceneId).get("children"))
                .as("отклонённый PUT с чужим scene_id не должен был тронуть сцену под ним")
                .hasSize(2);
    }

    /**
     * I3 (найдено ревью 5285aa4): самый разрушительный запрос — пустой {@code components} — был
     * единственным, что шёл без проверки версии, потому что {@code requireBaseUnlessRestoring}
     * искал сцены по присланным dto, а по пустому списку не находил ни одной. Проверка версии
     * теперь ключуется по {@code scene_id}, а не по содержимому тела.
     */
    @Test
    void emptyComponentsWithStaleBase_isRejectedAsVersionConflict() throws Exception {
        long sceneId = newScene();
        saveComponents(twoComponents(sceneId));
        Integer base = currentVersion(sceneId, "scenes");

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[],\"scene_id\":" + sceneId
                                + ",\"based_on_version\":" + (base + 100)
                                + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isConflict());

        assertThat(getComponent(sceneId).get("children"))
                .as("отклонённый по версии PUT не должен был стереть детей сцены")
                .hasSize(2);
    }
}
