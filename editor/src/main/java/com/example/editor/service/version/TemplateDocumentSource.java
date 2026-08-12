package com.example.editor.service.version;

import com.example.editor.dto.template.TemplateCreateDto;
import com.example.editor.dto.template.TemplateResponseDto;
import com.example.editor.mapper.TemplateComponentMapper;
import com.example.editor.model.template.TemplateFacePlate;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.template.TemplateFacePlateRepository;
import com.example.editor.service.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateDocumentSource implements DocumentSource {

    private final TemplateFacePlateRepository templateRepository;
    private final TemplateComponentMapper componentMapper;
    private final ObjectMapper objectMapper;
    private final TemplateService templateService;

    /**
     * {@code TemplateService} внедряется лениво: он сам зависит от этого бина (снимок при
     * сохранении), и без прокси контекст не поднимется из-за цикла.
     */
    public TemplateDocumentSource(TemplateFacePlateRepository templateRepository,
                                  TemplateComponentMapper componentMapper,
                                  ObjectMapper objectMapper,
                                  @Lazy TemplateService templateService) {
        this.templateRepository = templateRepository;
        this.componentMapper = componentMapper;
        this.objectMapper = objectMapper;
        this.templateService = templateService;
    }

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

    /**
     * У шаблонов путь сохранения — полная синхронизация дерева ({@code mergeTree} удаляет то,
     * чего нет в присланном), поэтому восстановление это просто запись содержимого обратно.
     * <p>
     * Оговорка: компоненты внутри шаблона сопоставляются по имени, поэтому переименованный
     * после снимка компонент при восстановлении получит новый id. Ссылок на внутренности
     * шаблона в контуре нет — шаблон копируется при вставке, — так что это безвредно, но знать
     * стоит.
     */
    @Override
    public void restore(Long templateId, JsonNode content, String userName) {
        TemplateCreateDto dto = objectMapper.convertValue(content, TemplateCreateDto.class);
        templateService.updateTemplate(templateId, dto, userName, VersionKind.RESTORE);
    }
}
