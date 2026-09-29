package com.example.editor.service.version;

import com.example.editor.model.version.DocumentType;
import com.example.editor.service.ComponentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Выпуск проекта: дерево целиком в той форме, в какой его читает runtime. Восстанавливать выпуск
 * в черновик нельзя — это переписало бы все сцены проекта разом, мимо слияния и проверки версий.
 */
@Component
public class ProjectDocumentSource implements DocumentSource {

    private final ObjectMapper objectMapper;
    private final ComponentService componentService;

    /** Лениво — по той же причине, что в {@link ProjectDataDocumentSource}: цикл через DocumentVersionService. */
    public ProjectDocumentSource(ObjectMapper objectMapper, @Lazy ComponentService componentService) {
        this.objectMapper = objectMapper;
        this.componentService = componentService;
    }

    @Override
    public DocumentType type() {
        return DocumentType.PROJECT;
    }

    @Override
    public JsonNode contentOf(Long projectId) {
        return objectMapper.valueToTree(componentService.getById(projectId));
    }

    @Override
    public void restore(Long projectId, JsonNode content, String userName) {
        throw new IllegalStateException("Выпуск проекта не восстанавливается в черновик; "
                + "восстанавливайте версии отдельных сцен");
    }
}
