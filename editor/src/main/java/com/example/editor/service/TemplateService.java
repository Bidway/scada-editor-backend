package com.example.editor.service;

import com.example.editor.command.template.CreateTemplateCommand;
import com.example.editor.command.template.DeleteTemplateCommand;
import com.example.editor.command.template.UpdateTemplateCommand;
import com.example.editor.config.command.CommandManager;
import com.example.editor.dto.template.TemplateCreateDto;
import com.example.editor.dto.template.TemplateResponseDto;
import com.example.editor.mapper.TemplateComponentMapper;
import com.example.editor.model.template.TemplateFacePlate;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.template.TemplateFacePlateRepository;
import com.example.editor.repository.template.TemplateComponentRepository;
import com.example.editor.service.version.DocumentVersionService;
import com.example.editor.service.version.TemplateDocumentSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateFacePlateRepository templateRepository;
    private final TemplateComponentRepository componentRepository;
    private final TemplateComponentMapper componentMapper;
    private final CommandManager commandManager;
    private final DocumentVersionService versionService;
    private final TemplateDocumentSource templateDocumentSource;

    public TemplateResponseDto createTemplate(TemplateCreateDto dto, String userName,
                                              VersionKind kind) {
        CreateTemplateCommand command = new CreateTemplateCommand(
                templateRepository, componentRepository, componentMapper, dto, userName
        );
        TemplateResponseDto response = commandManager.execute(command);
        response.setVersion_no(snapshot(response.getId(), userName, kind));
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
    public TemplateResponseDto updateTemplate(Long templateId, TemplateCreateDto dto,
                                              String userName, VersionKind kind) {
        if (kind != VersionKind.RESTORE) {
            versionService.requireBase(DocumentType.TEMPLATE, templateId, dto.getBased_on_version());
        }
        UpdateTemplateCommand command = new UpdateTemplateCommand(
                templateRepository, componentRepository, componentMapper, templateId, dto, userName
        );
        TemplateResponseDto response = commandManager.execute(command);
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
        DeleteTemplateCommand command = new DeleteTemplateCommand(templateRepository, templateId, userName);
        commandManager.execute(command);
    }

    public List<TemplateResponseDto> getAllTemplates() {
        List<TemplateFacePlate> templates = templateRepository.findAll();
        return templates.stream()
                .map(template -> {
                    TemplateResponseDto dto = new TemplateResponseDto();
                    dto.setId(template.getId());
                    dto.setName(template.getName());
                    dto.setType(template.getType());
                    if (template.getRootComponent() != null) {
                        dto.setRootComponent(componentMapper.toDtoTree(template.getRootComponent()));
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

    public TemplateResponseDto getTemplateById(Long templateId) {
        TemplateFacePlate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalStateException("Template not found: " + templateId));

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
