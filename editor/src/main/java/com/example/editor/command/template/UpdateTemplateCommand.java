package com.example.editor.command.template;

import com.example.shared.command.Command;
import com.example.shared.command.CommandResult;
import com.example.editor.dto.template.TemplateCreateDto;
import com.example.editor.dto.template.TemplateResponseDto;
import com.example.editor.mapper.TemplateComponentMapper;
import com.example.editor.model.template.TemplateComponent;
import com.example.editor.model.template.TemplateFacePlate;
import com.example.editor.repository.template.TemplateComponentRepository;
import com.example.editor.repository.template.TemplateFacePlateRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpdateTemplateCommand implements Command<TemplateResponseDto> {

    private final TemplateFacePlateRepository templateRepository;
    private final TemplateComponentRepository componentRepository;
    private final TemplateComponentMapper componentMapper;
    private final Long templateId;
    private final TemplateCreateDto dto;
    private final String userName;

    @Override
    public CommandResult<TemplateResponseDto> execute() {

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

        TemplateResponseDto response = new TemplateResponseDto();
        response.setId(template.getId());
        response.setName(template.getName());
        response.setType(template.getType());
        response.setRootComponent(componentMapper.toDtoTree(rootComponent));

        return new CommandResult<>(userName, "template", template.getId(), "UPDATE_TEMPLATE", null, null, response);
    }
}