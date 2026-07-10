package com.example.editor.service;

import com.example.editor.command.template.CreateTemplateCommand;
import com.example.editor.command.template.DeleteTemplateCommand;
import com.example.editor.command.template.UpdateTemplateCommand;
import com.example.editor.config.command.CommandManager;
import com.example.editor.dto.template.TemplateCreateDto;
import com.example.editor.dto.template.TemplateResponseDto;
import com.example.editor.mapper.TemplateComponentMapper;
import com.example.editor.model.template.TemplateFacePlate;
import com.example.editor.repository.template.TemplateFacePlateRepository;
import com.example.editor.repository.template.TemplateComponentRepository;
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

    public TemplateResponseDto createTemplate(TemplateCreateDto dto, String userName) {
        CreateTemplateCommand command = new CreateTemplateCommand(
                templateRepository, componentRepository, componentMapper, dto, userName
        );
        return commandManager.execute(command);
    }

    public TemplateResponseDto updateTemplate(Long templateId, TemplateCreateDto dto, String userName) {
        UpdateTemplateCommand command = new UpdateTemplateCommand(
                templateRepository, componentRepository, componentMapper, templateId, dto, userName
        );
        return commandManager.execute(command);
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
