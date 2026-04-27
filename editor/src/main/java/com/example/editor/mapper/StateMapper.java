package com.example.editor.mapper;

import com.example.editor.dto.component.ComponentStateResponseDto;
import com.example.editor.model.template.TemplateComponentState;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring",
        uses = ComponentPropertyMapper.class)
public interface StateMapper {

    List<ComponentStateResponseDto> toDtoList(List<TemplateComponentState> state);
}
