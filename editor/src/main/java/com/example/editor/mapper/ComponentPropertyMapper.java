package com.example.editor.mapper;

import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.repository.component.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.*;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring")
public abstract class ComponentPropertyMapper {

    @Autowired
    protected ComponentRepository componentRepository;

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "component", source = "component_id", qualifiedByName = "mapComponent")
    @Mapping(target = "tagId", source = "tag_id")
    @Mapping(target = "propertyType", source = "property_type")
    @Mapping(target = "valueType", source = "value_type")
    @Mapping(target = "defaultValue", source = "default_value")
    @Mapping(target = "onChange", source = "onChange", qualifiedByName = "jsonToString")
    public abstract ComponentProperty toEntity(PropertyCreateDto dto);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "component", source = "component_id", qualifiedByName = "mapComponent")
    @Mapping(target = "tagId", source = "tag_id")
    @Mapping(target = "propertyType", source = "property_type")
    @Mapping(target = "valueType", source = "value_type")
    @Mapping(target = "defaultValue", source = "default_value")
    @Mapping(target = "onChange", source = "onChange", qualifiedByName = "jsonToString")
    public abstract void updateEntity(PropertyCreateDto dto, @MappingTarget ComponentProperty entity);

    @Mapping(target = "component_id", source = "component.id")
    @Mapping(target = "tag_id", source = "tagId")
    @Mapping(target = "property_type", source = "propertyType")
    @Mapping(target = "value_type", source = "valueType")
    @Mapping(target = "default_value", source = "defaultValue")
    @Mapping(target = "onChange", source = "onChange", qualifiedByName = "stringToJson")
    public abstract PropertyResponseDto toDto(ComponentProperty entity);

    @Named("mapComponent")
    protected Component mapComponent(Long id) {
        return componentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Component not found: " + id));
    }

    @Named("jsonToString")
    protected String jsonToString(JsonNode node) {
        return node != null ? node.toString() : null;
    }

    @Named("stringToJson")
    protected JsonNode stringToJson(String value) {
        try {
            return value != null ? new ObjectMapper().readTree(value) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
