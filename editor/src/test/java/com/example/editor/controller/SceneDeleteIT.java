package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/editor/components} по id самой сцены (а не компонента внутри неё).
 * <p>
 * {@code ComponentServiceImpl.delete} после удаления снимает версию для каждой затронутой
 * сцены ({@code snapshotScenes}), а снимок читает текущее содержимое сцены через
 * {@code SceneDocumentSource.contentOf}. Когда удаляемый id — это id самой сцены,
 * {@code SceneRootResolver.sceneRootIdOf} возвращает id той же сцены (она сама себе корень),
 * и после удаления {@code contentOf} ищет уже не существующую строку — падает
 * {@code NotFoundException("Scene not found: " + id)}, хотя перед запросом сцена была на месте.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SceneDeleteIT extends EditorApiTestSupport {

    @Test
    void deletingSceneItself_succeeds() throws Exception {
        long projectId = createProject("proj-" + System.nanoTime());
        long sceneId = createScene("scene-" + System.nanoTime(), projectId);

        deleteComponents(List.of(sceneId), null)
                .andExpect(status().isOk());
    }

    /**
     * scada-crk: удалять нечего — клиент должен это узнать, а не получить 200 без проверки версии.
     * Отказ целиком: существующий компонент из того же запроса тоже остаётся на месте.
     */
    @Test
    void deletingMissingId_answers404_andDeletesNothing() throws Exception {
        long sceneId = newScene();
        long pumpId = saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":"
                + sceneId + "}]").get(0).get("id").asLong();

        deleteComponents(List.of(pumpId, 999_999_999L), currentVersion(sceneId, "scenes"))
                .andExpect(status().isNotFound());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/editor/components/" + pumpId))
                .andExpect(status().isOk());
    }
}
