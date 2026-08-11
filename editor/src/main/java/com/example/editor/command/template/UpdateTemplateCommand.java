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

        // mapTree всегда строит дерево заново, поэтому прежнее надо снять с учёта руками:
        // связь template -> rootComponent объявлена без cascade и orphanRemoval, и старый
        // корень со всем поддеревом иначе просто оставался в базе. Заметить это по API
        // нельзя — шаблон отдаёт только текущий корень, — но в template_component копилось
        // по целому дереву на каждое сохранение, и выборки по template_id видели их все.
        TemplateComponent previousRoot = template.getRootComponent();

        TemplateComponent rootComponent =
                componentMapper.mapTree(dto.getRootComponent(), template);

        componentRepository.save(rootComponent);

        template.setRootComponent(rootComponent);
        // saveAndFlush, а не save: пока ссылка root_component_id в базе указывает на старый
        // корень, удалить его не даст внешний ключ.
        templateRepository.saveAndFlush(template);

        if (previousRoot != null && !previousRoot.getId().equals(rootComponent.getId())) {
            componentRepository.delete(previousRoot);
        }

        TemplateResponseDto response = new TemplateResponseDto();
        response.setId(template.getId());
        response.setName(template.getName());
        response.setType(template.getType());
        response.setRootComponent(componentMapper.toDtoTree(rootComponent));

        return new CommandResult<>(userName, "template", template.getId(), "UPDATE_TEMPLATE", null, null, response);
    }
}