package com.example.editor.service;

import com.example.editor.command.template.CreateTemplateCommand;
import com.example.editor.command.template.DeleteTemplateCommand;
import com.example.editor.command.template.UpdateTemplateCommand;
import com.example.editor.dto.TemplateCreateDto;
import com.example.editor.dto.TemplateResponseDto;
import com.example.editor.mapper.TemplateComponentMapper;
import com.example.editor.model.TemplateFacePlate;
import com.example.editor.repository.TemplateFacePlateRepository;
import com.example.editor.repository.TemplateComponentRepository;
import com.example.editor.config.command.CommandResult;
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

    // Создание шаблона
    public TemplateResponseDto createTemplate(TemplateCreateDto dto) {
        CreateTemplateCommand command = new CreateTemplateCommand(
                templateRepository, componentRepository, componentMapper, dto
        );
        CommandResult<TemplateResponseDto> result = command.execute();
        return result.getResult();
    }

    // Обновление шаблона
    public TemplateResponseDto updateTemplate(Long templateId, TemplateCreateDto dto) {
        UpdateTemplateCommand command = new UpdateTemplateCommand(
                templateRepository, componentRepository, componentMapper, templateId, dto
        );
        CommandResult<TemplateResponseDto> result = command.execute();
        return result.getResult();
    }

    // Удаление шаблона
    public void deleteTemplate(Long templateId) {
        DeleteTemplateCommand command = new DeleteTemplateCommand(templateRepository, templateId);
        command.execute();
    }

    // Получение всех шаблонов
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

    // Получение одного шаблона по id
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