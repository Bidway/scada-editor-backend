package com.example.editor.mapper;

import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.dto.scene.ScenesResponseDto;
import com.example.editor.model.Component;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ComponentMapper {

    @Named("mainToDto")
    @Mapping(target = "parent_id", source = "parent.id")
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
}

