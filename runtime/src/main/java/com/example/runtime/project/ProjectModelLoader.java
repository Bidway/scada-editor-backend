package com.example.runtime.project;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.session.TagSubscriptionIndex;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Выпуск проекта из editor → готовая {@link ProjectModel}. */
@Component
public class ProjectModelLoader {

    private final EditorClient editorClient;
    private final ObjectMapper mapper;

    public ProjectModelLoader(EditorClient editorClient, ObjectMapper objectMapper) {
        this.editorClient = editorClient;
        // DTO runtime уже ответа editor: лишние поля — норма, а не ошибка.
        this.mapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ProjectModel load(Long projectId, int versionNo) {
        JsonNode raw = editorClient.getProjectVersion(projectId, versionNo);
        if (raw == null || raw.isNull()) {
            throw new IllegalStateException("editor не отдал выпуск " + versionNo + " проекта " + projectId);
        }
        EditorComponentDto tree = mapper.convertValue(raw, EditorComponentDto.class);
        return new ProjectModel(versionNo, raw, tree, TagSubscriptionIndex.build(tree, projectId));
    }
}
