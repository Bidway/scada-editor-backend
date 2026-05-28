package com.example.editor.mapper;

import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.model.template.TemplateComponentProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TemplateComponentPropertyMapper {

    @Mapping(target = "component_id", source = "component.id")
    @Mapping(target = "tag_id", source = "tagId")
    @Mapping(target = "property_type", source = "propertyType")
    @Mapping(target = "value_type", source = "valueType")
    @Mapping(target = "default_value", source = "defaultValue")
    @Mapping(target = "onChange", source = "onChange", qualifiedByName = "stringToJson")
    PropertyResponseDto toDto(TemplateComponentProperty entity);

    List<PropertyResponseDto> toDtoList(List<TemplateComponentProperty> entities);

    @Named("stringToJson")
    default JsonNode stringToJson(String value) {
        try {
            return value != null ? new ObjectMapper().readTree(value) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
