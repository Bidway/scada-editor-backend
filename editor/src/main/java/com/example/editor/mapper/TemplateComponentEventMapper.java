package com.example.editor.mapper;

import com.example.editor.dto.component.EventResponseDto;
import com.example.editor.model.template.TemplateComponentEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TemplateComponentEventMapper {

    @Mapping(target = "event_type", source = "eventType")
    EventResponseDto toDto(TemplateComponentEvent entity);

    List<EventResponseDto> toDtoList(List<TemplateComponentEvent> entities);
}
