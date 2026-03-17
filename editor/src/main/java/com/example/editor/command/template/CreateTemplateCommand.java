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
public class CreateTemplateCommand implements Command<TemplateResponseDto> {

    private final TemplateFacePlateRepository templateRepository;
    private final TemplateComponentRepository componentRepository;
    private final TemplateComponentMapper componentMapper;
    private final TemplateCreateDto dto;

    @Override
    public CommandResult<TemplateResponseDto> execute() {

        // 1️⃣ Создаём шаблон
        TemplateFacePlate template = new TemplateFacePlate();
        template.setName(dto.getName());
        template.setType(dto.getType());
        templateRepository.save(template);

        // 2️⃣ Создаём компоненты рекурсивно, пока parent=null
        var componentsFlat = componentMapper.toEntitiesFlat(
                java.util.Collections.singletonList(dto.getRootComponent()),
                template
        );

        // 3️⃣ Сохраняем все компоненты, Hibernate проставит id
        var savedComponents = componentRepository.saveAll(componentsFlat);

        // 4️⃣ Устанавливаем rootComponent шаблона
        TemplateComponent rootComponent = savedComponents.get(0);
        template.setRootComponent(rootComponent);
        templateRepository.save(template);

        // 5️⃣ Конвертируем в ResponseDto
        TemplateResponseDto response = new TemplateResponseDto();
        response.setId(template.getId());
        response.setName(template.getName());
        response.setType(template.getType());
        response.setRootComponent(componentMapper.toDtoTree(rootComponent));

        return new CommandResult<>("david", "template", 1L, "CREATE_TEMPLATE", null, null, response);
    }
}