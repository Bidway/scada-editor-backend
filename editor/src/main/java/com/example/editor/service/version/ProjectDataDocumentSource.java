package com.example.editor.service.version;

import com.example.editor.dto.data.ProjectDataSetDto;
import com.example.editor.model.version.DocumentType;
import com.example.editor.service.data.ProjectDataService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ProjectDataDocumentSource implements DocumentSource {

    private final ObjectMapper objectMapper;
    private final ProjectDataService projectDataService;

    /**
     * {@code ProjectDataService} внедряется лениво: он зависит от {@code DocumentVersionService},
     * а тот — от списка всех {@code DocumentSource}, включая этот. Без прокси — цикл.
     */
    public ProjectDataDocumentSource(ObjectMapper objectMapper, @Lazy ProjectDataService projectDataService) {
        this.objectMapper = objectMapper;
        this.projectDataService = projectDataService;
    }

    @Override
    public DocumentType type() {
        return DocumentType.PROJECT_DATA;
    }

    @Override
    public JsonNode contentOf(Long projectId) {
        return objectMapper.valueToTree(projectDataService.snapshot(projectId));
    }

    @Override
    public void restore(Long projectId, JsonNode content, String userName) {
        projectDataService.restoreSet(projectId, objectMapper.convertValue(content, ProjectDataSetDto.class), userName);
    }
}
