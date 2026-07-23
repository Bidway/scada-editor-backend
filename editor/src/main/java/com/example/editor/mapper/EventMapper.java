package com.example.editor.mapper;

import com.example.editor.dto.component.EventResponseDto;
import com.example.editor.model.component.ComponentEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface EventMapper {

    @Mapping(target = "event_type", source = "eventType")
    EventResponseDto toDto(ComponentEvent entity);

    List<EventResponseDto> toDtoList(List<ComponentEvent> entities);
}
