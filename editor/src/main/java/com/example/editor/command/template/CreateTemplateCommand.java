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
public class CreateTemplateCommand implements Command<TemplateResponseDto> {

    private final TemplateFacePlateRepository templateRepository;
    private final TemplateComponentRepository componentRepository;
    private final TemplateComponentMapper componentMapper;
    private final TemplateCreateDto dto;

    @Override
    public CommandResult<TemplateResponseDto> execute() {

        // 1️⃣ Создаём template
        TemplateFacePlate template = new TemplateFacePlate();
        template.setName(dto.getName());
        template.setType(dto.getType());
        templateRepository.save(template);

        // 2️⃣ Строим ДЕРЕВО компонентов
        TemplateComponent rootComponent =
                componentMapper.mapTree(dto.getRootComponent(), template);

        // 3️⃣ Сохраняем дерево (cascade сохранит всё)
        componentRepository.save(rootComponent);

        // 4️⃣ Привязываем root к template
        template.setRootComponent(rootComponent);
        templateRepository.save(template);

        // 5️⃣ Формируем ответ
        TemplateResponseDto response = new TemplateResponseDto();
        response.setId(template.getId());
        response.setName(template.getName());
        response.setType(template.getType());
        response.setRootComponent(componentMapper.toDtoTree(rootComponent));

        return new CommandResult<>(
                "david",
                "template",
                template.getId(),
                "CREATE_TEMPLATE",
                null,
                null,
                response
        );
    }
}