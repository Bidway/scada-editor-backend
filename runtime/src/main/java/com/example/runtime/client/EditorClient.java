package com.example.runtime.client;

import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.client.dto.EditorRecipeDto;
import com.example.runtime.config.RuntimeProperties;
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
 */
@Component
@Slf4j
public class EditorClient {

    private final RestClient restClient;

    public EditorClient(RuntimeProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getEditorBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public EditorComponentDto getProjectTree(Long projectId) {
        log.debug("Fetching project tree {} from editor", projectId);
        return restClient.get()
                .uri("/api/editor/components/{id}", projectId)
                .retrieve()
                .body(EditorComponentDto.class);
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
