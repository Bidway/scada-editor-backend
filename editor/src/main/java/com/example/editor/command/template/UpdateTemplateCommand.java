package com.example.editor.command.template;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.dto.TemplateCreateDto;
import com.example.editor.dto.TemplateResponseDto;
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

        // 1️⃣ Находим шаблон
        TemplateFacePlate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalStateException("Template not found: " + templateId));

        template.setName(dto.getName());
        template.setType(dto.getType());
        templateRepository.save(template);

        // 2️⃣ Удаляем старые компоненты (чистим дерево)
        if (template.getRootComponent() != null) {
            componentRepository.delete(template.getRootComponent());
        }

        // 3️⃣ Создаём новые компоненты
        var componentsFlat = componentMapper.toEntitiesFlat(
                java.util.Collections.singletonList(dto.getRootComponent()),
                template
        );
        var savedComponents = componentRepository.saveAll(componentsFlat);

        // 4️⃣ Обновляем rootComponent
        TemplateComponent rootComponent = savedComponents.get(0);
        template.setRootComponent(rootComponent);
        templateRepository.save(template);

        // 5️⃣ Формируем ResponseDto
        TemplateResponseDto response = new TemplateResponseDto();
        response.setId(template.getId());
        response.setName(template.getName());
        response.setType(template.getType());
        response.setRootComponent(componentMapper.toDtoTree(rootComponent));

        return new CommandResult<>("david", "template", 1L, "UPDATE_TEMPLATE", null, null, response);
    }
}