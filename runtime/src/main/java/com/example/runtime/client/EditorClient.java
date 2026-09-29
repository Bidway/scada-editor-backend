package com.example.runtime.client;

import com.example.runtime.client.dto.EditorRuntimeFlag;
import com.example.runtime.client.dto.EditorRecipeDto;
import com.example.runtime.config.RuntimeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Единственная точка обращения runtime -> editor. Дерево проекта запрашивается один раз
 * при старте сессии мониторинга, а вот {@link #getRecipe(String)} зовётся с горячих путей
 * {@code ProcedureExecutionService}: на каждый тик планировщика, на каждое изменение тега
 * сессии и на каждый запрос статуса процедуры. Поэтому у клиента заданы конечные таймауты
 * подключения и чтения: зависший (а не упавший) editor иначе подвесил бы вызывающий тред
 * пула или HTTP-тред без ограничения по времени.
 *
 * <p>Дереву проекта — свой клиент с длинным таймаутом чтения: оно запрашивается только при
 * активации проекта, а у большого проекта (10554, ~4 МБ) editor отдаёт его дольше 5 с
 * (scada-kdxq).
 */
@Component
@Slf4j
public class EditorClient {

    private final RestClient restClient;
    private final RestClient treeClient;

    public EditorClient(RuntimeProperties properties) {
        this.restClient = client(properties, Duration.ofSeconds(5));
        this.treeClient = client(properties, Duration.ofSeconds(30));
    }

    private static RestClient client(RuntimeProperties properties, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(properties.getEditorBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Флаг и prod-выпуск — по таблице editor, а не по топику. Топик только сигнал: в нём может
     * остаться запись, которой в editor уже нет (scada-ocqj).
     */
    public EditorRuntimeFlag getRuntime(Long projectId) {
        EditorRuntimeFlag flag = restClient.get()
                .uri("/api/editor/projects/{id}/runtime", projectId)
                .retrieve()
                .body(EditorRuntimeFlag.class);
        return flag != null ? flag : new EditorRuntimeFlag(false, null);
    }

    /**
     * Дерево выпуска проекта как есть. Живое дерево ({@code /api/editor/components/{id}}) runtime
     * не читает: монитор и логика работают по prod, черновик видит только редактор.
     */
    public JsonNode getProjectVersion(Long projectId, int versionNo) {
        log.debug("Fetching project {} release {} from editor", projectId, versionNo);
        return treeClient.get()
                .uri("/api/editor/projects/{id}/versions/{n}", projectId, versionNo)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Таблицы данных проекта — ответ {@code GET /api/editor/projects/{id}/data} как есть. */
    public JsonNode getProjectData(Long projectId) {
        log.debug("Fetching project data {} from editor", projectId);
        return restClient.get()
                .uri("/api/editor/projects/{id}/data", projectId)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Определение процедурного рецепта (манифест тегов + шаги), как есть, без резолва. */
    public EditorRecipeDto getRecipe(String recipeId) {
        log.debug("Fetching recipe {} from editor", recipeId);
        return restClient.get()
                .uri("/api/editor/recipes/{id}", recipeId)
                .retrieve()
                .body(EditorRecipeDto.class);
    }
}
