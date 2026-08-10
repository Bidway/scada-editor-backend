package com.example.editor.service.Impl;

import com.example.editor.config.command.CommandManager;
import com.example.editor.command.property.CreatePropertyCommand;
import com.example.editor.command.property.DeletePropertyCommand;
import com.example.editor.command.property.UpdatePropertyCommand;
import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.mapper.ComponentPropertyMapper;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.recipe.RecipeValueRepository;
import com.example.editor.service.ComponentPropertyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComponentPropertyServiceImpl implements ComponentPropertyService {

    private final ComponentPropertyRepository repository;
    private final RecipeValueRepository recipeValueRepository;
    private final CommandManager commandManager;
    private final ComponentPropertyMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public PropertyResponseDto create(PropertyCreateDto dto, String userName) {
        dto.setName(normalize(dto.getName()));
        ComponentProperty entity = mapper.toEntity(dto);
        Long componentId = entity.getComponent() == null ? null : entity.getComponent().getId();
        if (componentId != null && repository.existsByComponentIdAndName(componentId, entity.getName())) {
            throw new IllegalStateException(
                    "Property name '" + entity.getName() + "' already exists in component " + componentId
                            + "; names must be unique within a component");
        }
        CreatePropertyCommand command =
                new CreatePropertyCommand(repository, mapper, objectMapper, entity, userName);
        return commandManager.execute(command);
    }

    /**
     * Точечное обновление свойства — единственное место, где переименование строки распознаваемо
     * (известны и старое имя, и id). Поэтому именно здесь значения наборов переносятся на новое
     * имя: при массовом сохранении таблицы строки сопоставляются по имени, поэтому переименование
     * там неотличимо от «удалили строку, добавили другую», и перенос невозможен.
     */
    @Override
    @Transactional
    public PropertyResponseDto update(Long id, PropertyCreateDto dto, String userName) {
        ComponentProperty existing = repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Property not found: " + id));
        Long componentId = existing.getComponent() == null ? null : existing.getComponent().getId();
        String oldName = existing.getName();

        String newName = normalize(dto.getName());
        dto.setName(newName);
        if (newName != null && componentId != null
                && repository.existsByComponentIdAndNameAndIdNot(componentId, newName, id)) {
            throw new IllegalStateException(
                    "Property name '" + newName + "' already exists in component " + componentId
                            + "; names must be unique within a component");
        }

        UpdatePropertyCommand command =
                new UpdatePropertyCommand(repository, mapper, objectMapper, id, dto, userName);
        PropertyResponseDto response = commandManager.execute(command);

        if (newName != null && componentId != null && !newName.equals(oldName)) {
            int moved = recipeValueRepository.renameRow(componentId, oldName, newName);
            if (moved > 0) {
                log.info("Renamed row '{}' -> '{}' in component {}: {} value(s) moved to the new name",
                        oldName, newName, componentId, moved);
            }
        }
        return response;
    }

    @Override
    public void delete(Long id, String userName) {
        DeletePropertyCommand command = new DeletePropertyCommand(repository, id, userName, objectMapper);
        commandManager.execute(command);
    }

    /** Имя — ключ привязки значений набора, поэтому хранится без краевых пробелов. */
    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
