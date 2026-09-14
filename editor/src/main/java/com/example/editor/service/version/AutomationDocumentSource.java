package com.example.editor.service.version;

import com.example.editor.dto.automation.AutomationSetDto;
import com.example.editor.model.version.DocumentType;
import com.example.editor.service.automation.AutomationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AutomationDocumentSource implements DocumentSource {

    private final ObjectMapper objectMapper;
    private final AutomationService automationService;

    /**
     * {@code AutomationService} внедряется лениво: он зависит от {@code DocumentVersionService},
     * а тот — от списка всех {@code DocumentSource}, включая этот. Без прокси — цикл.
     */
    public AutomationDocumentSource(ObjectMapper objectMapper, @Lazy AutomationService automationService) {
        this.objectMapper = objectMapper;
        this.automationService = automationService;
    }

    @Override
    public DocumentType type() {
        return DocumentType.AUTOMATION;
    }

    @Override
    public JsonNode contentOf(Long projectId) {
        return objectMapper.valueToTree(automationService.snapshot(projectId));
    }

    /**
     * Восстановление — обычное сохранение снимка целиком, со своей версией и строкой outbox:
     * откат задачи тоже должен доехать до {@code automation}.
     */
    @Override
    public void restore(Long projectId, JsonNode content, String userName) {
        automationService.restoreSet(projectId, objectMapper.convertValue(content, AutomationSetDto.class), userName);
    }
}
