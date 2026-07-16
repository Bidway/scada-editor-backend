package com.example.editor.mapper;

import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.repository.component.ComponentRepository;
import org.mapstruct.*;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring")
public abstract class ComponentPropertyMapper {

    @Autowired
    protected ComponentRepository componentRepository;

    // onChange — String в DTO и в сущности, маппится по имени напрямую (сырой JS,
    // без JSON-обёртки).
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "component", source = "component_id", qualifiedByName = "mapComponent")
    @Mapping(target = "tagId", source = "tag_id")
    @Mapping(target = "propertyType", source = "property_type")
    @Mapping(target = "valueType", source = "value_type")
    @Mapping(target = "defaultValue", source = "default_value")
    public abstract ComponentProperty toEntity(PropertyCreateDto dto);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "component", source = "component_id", qualifiedByName = "mapComponent")
    @Mapping(target = "tagId", source = "tag_id")
    @Mapping(target = "propertyType", source = "property_type")
    @Mapping(target = "valueType", source = "value_type")
    @Mapping(target = "defaultValue", source = "default_value")
    public abstract void updateEntity(PropertyCreateDto dto, @MappingTarget ComponentProperty entity);

    @Mapping(target = "component_id", source = "component.id")
    @Mapping(target = "tag_id", source = "tagId")
    @Mapping(target = "property_type", source = "propertyType")
    @Mapping(target = "value_type", source = "valueType")
    @Mapping(target = "default_value", source = "defaultValue")
    public abstract PropertyResponseDto toDto(ComponentProperty entity);

    @Named("mapComponent")
    protected Component mapComponent(Long id) {
        return componentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Component not found: " + id));
    }
}
