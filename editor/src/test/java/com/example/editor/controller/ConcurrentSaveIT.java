package com.example.editor.controller;

import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.repository.version.DocumentVersionRepository;
import com.example.editor.service.version.DocumentVersionService;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сохранение и запись версии — одна операция, а не две подряд.
 * <p>
 * Раньше проверка базовой версии, запись данных и запись снимка шли тремя разными транзакциями
 * (scada-78j). Отсюда два исхода, одинаково плохих: сбой снимка оставлял данные записанными без
 * следа в истории, а два одновременных сохранения оба проходили проверку, оба коммитили данные и
 * второй бился о {@code UNIQUE (target_type, target_id, version_no)} — клиент получал 500, хотя
 * его правка уже лежала в базе.
 * <p>
 * <b>Про подмену бина.</b> {@code @MockitoSpyBean} поднимает второй контекст Spring вопреки
 * общему правилу модуля (все IT аннотируются одинаково, см. scada-90m). Это осознанная цена:
 * «данные и версия коммитятся вместе» иначе не наблюдаемо — нужен сбой ровно в записи версии,
 * а подходящих данных, которые роняли бы только её, не существует.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentSaveIT extends EditorApiTestSupport {

    @MockitoSpyBean
    private DocumentVersionService versionService;

    @MockitoSpyBean
    private DocumentVersionRepository versionRepository;

    private DocumentVersion versionOf(long sceneId, int versionNo) {
        return versionRepository
                .findByTargetTypeAndTargetIdOrderByVersionNoDesc(DocumentType.SCENE, sceneId)
                .stream()
                .filter(v -> v.getVersionNo() == versionNo)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Версии " + versionNo + " нет — сцена сохранилась не так, как ждёт тест"));
    }

    private String pumpComponents(long sceneId, String setpoint) {
        return "[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId
                + ",\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\",\"default_value\":\"" + setpoint + "\"}]}]";
    }

    private String pump(long sceneId, String setpoint) {
        return pump(sceneId, setpoint, null);
    }

    private String pump(long sceneId, String setpoint, Integer basedOnVersion) {
        return "{\"components\":" + pumpComponents(sceneId, setpoint)
                + (basedOnVersion == null ? "" : ",\"based_on_version\":" + basedOnVersion)
                + ",\"save_kind\":\"MANUAL\"}";
    }

    /**
     * Гонка двух сохранений, воспроизведённая без второго потока.
     * <p>
     * Настоящая гонка — это устаревшее чтение: оба сохранения видят последнюю версию N, оба
     * считают свою N+1, первое её занимает. Здесь то же самое сделано детерминированно: проверка
     * базовой версии получает настоящий номер (иначе она отвергнет запрос сама и до записи дело
     * не дойдёт), а запись снимка — устаревший, и вычисляет уже занятый номер. Дальше работает
     * настоящий {@code document_version_uk} на настоящей базе, а не подделка ошибки.
     * <p>
     * Проигравший обязан получить 409 того же вида, что и обычное расхождение версии, — фронт
     * его уже умеет обрабатывать, — и не оставить за собой записанных данных.
     */
    @Test
    void saveThatLosesTheVersionRace_answers409_andWritesNoData() throws Exception {
        long sceneId = newScene();
        saveComponents(pumpComponents(sceneId, "10"), null, "MANUAL");
        saveComponents(pumpComponents(sceneId, "20"), 1, "MANUAL");

        DocumentVersion current = versionOf(sceneId, 2);
        DocumentVersion stale = versionOf(sceneId, 1);

        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> Optional.of(reads.incrementAndGet() >= 2 ? stale : current))
                .when(versionRepository)
                .findTopByTargetTypeAndTargetIdOrderByVersionNoDesc(any(), any());

        String body = mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pump(sceneId, "30", 2)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("error").asText())
                .as("форма 409 обязана совпадать с обычным расхождением версии")
                .isEqualTo("version_mismatch");
        assertThat(response.get("current_version").asInt())
                .as("клиенту нужен номер, от которого пересохраняться")
                .isEqualTo(2);
        assertThat(getComponent(sceneId).get("children"))
                .as("проигравший гонку не оставляет за собой записанных данных")
                .hasSize(2);
    }

    @Test
    void saveThatFailsToRecordVersion_writesNoData() throws Exception {
        long sceneId = newScene();
        doThrow(new RuntimeException("снимок не записался"))
                .when(versionService).record(any(), any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pump(sceneId, "10")))
                .andExpect(status().is5xxServerError());

        assertThat(getComponent(sceneId).get("children"))
                .as("версия не записалась — значит, и данных остаться не должно")
                .isEmpty();
    }
}
