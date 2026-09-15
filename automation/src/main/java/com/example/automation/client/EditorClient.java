package com.example.automation.client;

import com.example.automation.config.AutomationProperties;
import com.example.automation.engine.ProjectDataFetcher;
import com.example.scriptcore.ProjectData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Единственная точка обращения automation -> editor: таблицы данных проекта. Конечные таймауты —
 * зависший editor не должен держать поток загрузки дольше нескольких секунд.
 */
@Component
public class EditorClient implements ProjectDataFetcher {

    private final RestClient restClient;

    public EditorClient(AutomationProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getEditorBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public ProjectData fetch(long projectId) {
        JsonNode body = restClient.get()
                .uri("/api/editor/projects/{id}/data", projectId)
                .retrieve()
                .body(JsonNode.class);
        return ProjectData.parse(body);
    }
}
