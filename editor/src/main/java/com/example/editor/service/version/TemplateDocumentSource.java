package com.example.editor.service.version;

import com.example.editor.dto.template.TemplateResponseDto;
import com.example.editor.mapper.TemplateComponentMapper;
import com.example.editor.model.template.TemplateFacePlate;
import com.example.editor.model.version.DocumentType;
import com.example.editor.repository.template.TemplateFacePlateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TemplateDocumentSource implements DocumentSource {

    private final TemplateFacePlateRepository templateRepository;
    private final TemplateComponentMapper componentMapper;
    private final ObjectMapper objectMapper;

    @Override
    public DocumentType type() {
        return DocumentType.TEMPLATE;
    }

    /**
     * Своя транзакция по той же причине, что у сцен: дерево шаблона ленивое, и обход вне
     * веб-запроса иначе падает {@code LazyInitializationException}.
     */
    @Override
    @Transactional(readOnly = true)
    public JsonNode contentOf(Long templateId) {
        TemplateFacePlate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalStateException("Template not found: " + templateId));

        TemplateResponseDto dto = new TemplateResponseDto();
        dto.setId(template.getId());
        dto.setName(template.getName());
        dto.setType(template.getType());
        dto.setRootComponent(componentMapper.toDtoTree(template.getRootComponent()));
        return objectMapper.valueToTree(dto);
    }

    @Override
    public void restore(Long templateId, JsonNode content, String userName) {
        throw new UnsupportedOperationException("Восстановление шаблона — задача 8");
    }
}
