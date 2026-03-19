package com.example.editor.command.template;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.dto.template.TemplateCreateDto;
import com.example.editor.dto.template.TemplateResponseDto;
import com.example.editor.mapper.TemplateComponentMapper;
import com.example.editor.model.TemplateComponent;
import com.example.editor.model.TemplateFacePlate;
import com.example.editor.repository.TemplateComponentRepository;
import com.example.editor.repository.TemplateFacePlateRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpdateTemplateCommand implements Command<TemplateResponseDto> {

    private final TemplateFacePlateRepository templateRepository;
    private final TemplateComponentRepository componentRepository;
    private final TemplateComponentMapper componentMapper;
    private final Long templateId;
    private final TemplateCreateDto dto;

    @Override
    public CommandResult<TemplateResponseDto> execute() {

        TemplateFacePlate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalStateException("Template not found: " + templateId));

        template.setName(dto.getName());
        template.setType(dto.getType());
        templateRepository.save(template);

        TemplateComponent rootComponent =
                componentMapper.mapTree(dto.getRootComponent(), template);

        componentRepository.save(rootComponent);

        template.setRootComponent(rootComponent);
        templateRepository.save(template);

        TemplateResponseDto response = new TemplateResponseDto();
        response.setId(template.getId());
        response.setName(template.getName());
        response.setType(template.getType());
        response.setRootComponent(componentMapper.toDtoTree(rootComponent));

        return new CommandResult<>("david", "template", 1L, "UPDATE_TEMPLATE", null, null, response);
    }
}