package com.example.editor.mapper;

import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.project.ProjectCreateResponseDto;
import com.example.editor.dto.project.ProjectsResponseDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.dto.scene.ScenesResponseDto;
import com.example.editor.model.component.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring",
        uses = {ComponentPropertyMapper.class, ScriptMapper.class, BindingMapper.class, EventMapper.class})
public interface ComponentMapper {
    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Named("mainToDto")
    @Mapping(target = "parent_id", source = "parent.id")
    @Mapping(target = "properties", source = "properties")
    @Mapping(target = "scripts", source = "scripts")
    @Mapping(target = "bindings", source = "bindings")
    @Mapping(target = "events", source = "events")
    ComponentResponseDto toDto(Component entity);

    @Mapping(target = "parent_key", source = "parent.id")
    SceneCreateResponseDto toSceneCreateDto(Component entity);

    @Mapping(target = "parent_id", source = "parent.id")
    ProjectCreateResponseDto toProjectCreateDto(Component entity);

    @IterableMapping(qualifiedByName = "mainToDto")
    List<ComponentResponseDto> toDtoList(List<Component> entities);

    @Mapping(target = "project_id", source = "parent.id")
    ScenesResponseDto toScenesDto(Component entity);

    List<ScenesResponseDto> toScenesDtoList(List<Component> entities);

    ProjectsResponseDto toProjectsDto(Component entity);

    List<ProjectsResponseDto> toProjectsDtoList(List<Component> entities);

    @AfterMapping
    default void mapChildren(Component entity,
                             @MappingTarget ComponentResponseDto dto) {

        if (entity.getChildren() != null) {
            dto.setChildren(
                    entity.getChildren()
                            .stream()
                            .map(this::toDto)
                            .toList()
            );
        }
    }
    default JsonNode map(String value) {
        if (value == null) return null;
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + value, e);
        }
    }
}

