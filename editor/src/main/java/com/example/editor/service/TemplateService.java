package com.example.editor.service;

import com.example.editor.dto.template.TemplateCreateDto;
import com.example.editor.dto.template.TemplateResponseDto;
import com.example.editor.mapper.TemplateComponentMapper;
import com.example.editor.model.template.TemplateComponent;
import com.example.editor.model.template.TemplateFacePlate;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.template.TemplateFacePlateRepository;
import com.example.editor.repository.template.TemplateComponentRepository;
import com.example.editor.service.version.DocumentVersionService;
import com.example.editor.service.version.TemplateDocumentSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateFacePlateRepository templateRepository;
    private final TemplateComponentRepository componentRepository;
    private final TemplateComponentMapper componentMapper;
    private final DocumentVersionService versionService;
    private final TemplateDocumentSource templateDocumentSource;

    /** Данные и снимок — одна транзакция, как у сцены (scada-78j). */
    @Transactional
    public TemplateResponseDto createTemplate(TemplateCreateDto dto, String userName,
                                              VersionKind kind) {
        TemplateFacePlate template = new TemplateFacePlate();
        template.setName(dto.getName());
        template.setType(dto.getType());
        templateRepository.save(template);

        TemplateComponent rootComponent = componentMapper.mapTree(dto.getRootComponent(), template);
        componentRepository.save(rootComponent);

        template.setRootComponent(rootComponent);
        templateRepository.save(template);

        TemplateResponseDto response = toResponse(template);
        response.setVersion_no(snapshot(template.getId(), userName, kind));
        return response;
    }

    public TemplateResponseDto createTemplate(TemplateCreateDto dto, String userName) {
        return createTemplate(dto, userName, VersionKind.MANUAL);
    }

    /**
     * Проверка базовой версии — до выполнения команды: отказ обязан случиться раньше, чем
     * что-либо записано. У создания её нет — документа ещё не существует, базе взяться неоткуда.
     * <p>
     * Восстановление ({@code kind = RESTORE}) проверку пропускает: оно всегда дописывает версию
     * поверх текущей и не несёт {@code based_on_version} в присланном dto (он собран из снимка).
     * Подделать этот путь клиент не может — {@code save_kind=RESTORE} отклоняется раньше, в
     * {@link com.example.editor.model.version.VersionKinds#orManual}.
     */
    @Transactional
    public TemplateResponseDto updateTemplate(Long templateId, TemplateCreateDto dto,
                                              String userName, VersionKind kind) {
        if (kind != VersionKind.RESTORE) {
            versionService.requireBase(DocumentType.TEMPLATE, templateId, dto.getBased_on_version());
        }
        TemplateFacePlate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalStateException("Template not found: " + templateId));

        template.setName(dto.getName());
        template.setType(dto.getType());
        templateRepository.save(template);

        // Присланное дерево сливается с существующим, а не строится заново: иначе каждое
        // сохранение выдаёт новые id всему поддереву (scada-eap). Корень при этом остаётся тем
        // же объектом, поэтому снимать прежний с учёта больше не нужно — выпавшие узлы уносит
        // orphanRemoval на children.
        TemplateComponent rootComponent = componentMapper.mergeTree(
                template.getRootComponent(), dto.getRootComponent(), template, null);
        componentRepository.save(rootComponent);

        template.setRootComponent(rootComponent);
        templateRepository.save(template);

        TemplateResponseDto response = toResponse(template);
        response.setVersion_no(snapshot(templateId, userName, kind));
        return response;
    }

    public TemplateResponseDto updateTemplate(Long templateId, TemplateCreateDto dto,
                                              String userName) {
        return updateTemplate(templateId, dto, userName, VersionKind.MANUAL);
    }

    private Integer snapshot(Long templateId, String userName, VersionKind kind) {
        return versionService.record(DocumentType.TEMPLATE, templateId,
                templateDocumentSource.contentOf(templateId), userName, kind, null).getVersionNo();
    }

    public void deleteTemplate(Long templateId, String userName) {
        templateRepository.deleteById(templateId);
    }

    public List<TemplateResponseDto> getAllTemplates() {
        return templateRepository.findAll().stream().map(this::toResponse).toList();
    }

    public TemplateResponseDto getTemplateById(Long templateId) {
        return toResponse(templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalStateException("Template not found: " + templateId)));
    }

    /**
     * Сборка ответа из сущности. Раньше эти семь строк лежали в файле четырьмя копиями, и копии
     * уже разошлись: чтение проверяло {@code rootComponent} на null, запись — нет.
     */
    private TemplateResponseDto toResponse(TemplateFacePlate template) {
        TemplateResponseDto dto = new TemplateResponseDto();
        dto.setId(template.getId());
        dto.setName(template.getName());
        dto.setType(template.getType());
        if (template.getRootComponent() != null) {
            dto.setRootComponent(componentMapper.toDtoTree(template.getRootComponent()));
        }
        return dto;
    }
}
