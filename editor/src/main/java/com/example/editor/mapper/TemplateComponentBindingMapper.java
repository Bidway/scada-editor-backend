package com.example.editor.mapper;

import com.example.editor.dto.component.BindingResponseDto;
import com.example.editor.model.template.TemplateComponentBinding;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TemplateComponentBindingMapper {

    @Mapping(target = "component_property_id", source = "componentProperty.id")
    @Mapping(target = "component_property_name", source = "componentProperty.name")
    BindingResponseDto toDto(TemplateComponentBinding entity);

    List<BindingResponseDto> toDtoList(List<TemplateComponentBinding> entities);
}
