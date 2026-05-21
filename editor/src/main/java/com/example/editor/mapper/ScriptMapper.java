package com.example.editor.mapper;

import com.example.editor.dto.component.ScriptResponseDto;
import com.example.editor.model.component.Script;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ScriptMapper {

    ScriptResponseDto toDto(Script entity);

    List<ScriptResponseDto> toDtoList(List<Script> entities);
}
