package com.example.editor.mapper;

import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.model.template.TemplateComponentProperty;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TemplateComponentPropertyMapper {

    // onChange — String в DTO и в сущности, маппится по имени напрямую (сырой JS).
    @Mapping(target = "component_id", source = "component.id")
    @Mapping(target = "tag_id", source = "tagId")
    @Mapping(target = "property_type", source = "propertyType")
    @Mapping(target = "value_type", source = "valueType")
    @Mapping(target = "default_value", source = "defaultValue")
    PropertyResponseDto toDto(TemplateComponentProperty entity);

    List<PropertyResponseDto> toDtoList(List<TemplateComponentProperty> entities);
}
