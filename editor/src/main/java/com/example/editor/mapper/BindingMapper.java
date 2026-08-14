package com.example.editor.mapper;

import com.example.editor.dto.component.BindingResponseDto;
import com.example.editor.model.component.Binding;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BindingMapper {

    @Mapping(target = "component_property_id", source = "componentProperty.id")
    @Mapping(target = "component_property_name", source = "componentProperty.name")
    BindingResponseDto toDto(Binding entity);

    List<BindingResponseDto> toDtoList(List<Binding> entities);
}
