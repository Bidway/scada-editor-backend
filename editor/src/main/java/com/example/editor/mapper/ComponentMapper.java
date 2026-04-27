package com.example.editor.mapper;

import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.dto.scene.ScenesResponseDto;
import com.example.editor.model.component.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring",
        uses = ComponentPropertyMapper.class)
public interface ComponentMapper {
    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Named("mainToDto")
    @Mapping(target = "parent_id", source = "parent.id")
    @Mapping(target = "properties", source = "properties")
    ComponentResponseDto toDto(Component entity);

    @Mapping(target = "parent_key", source = "parent.id")
    SceneCreateResponseDto toSceneCreateDto(Component entity);

    @IterableMapping(qualifiedByName = "mainToDto")
    List<ComponentResponseDto> toDtoList(List<Component> entities);

    List<ScenesResponseDto> toScenesDtoList(List<Component> entities);

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

