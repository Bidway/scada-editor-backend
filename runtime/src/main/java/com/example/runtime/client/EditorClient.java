package com.example.runtime.client;

import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.client.dto.ResolvedRecipeValue;
import com.example.runtime.config.RuntimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Единственная точка обращения runtime -> editor. Вызывается ровно один раз при
 * старте сессии мониторинга (не на горячем пути тегов), поэтому обычный
 * блокирующий REST-вызов здесь уместен и не создаёт нагрузки на editor.
 */
@Component
@Slf4j
public class EditorClient {

    private final RestClient restClient;

    public EditorClient(RuntimeProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getEditorBaseUrl())
                .build();
    }

    public EditorComponentDto getProjectTree(Long projectId) {
        log.debug("Fetching project tree {} from editor", projectId);
        return restClient.get()
                .uri("/api/editor/components/{id}", projectId)
                .retrieve()
                .body(EditorComponentDto.class);
    }

    /** Резолв уставок рецепта для применения (запись в теги). Вызывается по действию оператора. */
    public List<ResolvedRecipeValue> getResolvedRecipe(Long recipeId) {
        log.debug("Fetching resolved recipe {} from editor", recipeId);
        return restClient.get()
                .uri("/api/editor/recipes/{id}/resolved", recipeId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ResolvedRecipeValue>>() {
                });
    }
}
