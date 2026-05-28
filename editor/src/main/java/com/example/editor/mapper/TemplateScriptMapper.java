package com.example.editor.mapper;

import com.example.editor.dto.component.ScriptResponseDto;
import com.example.editor.model.template.TemplateScript;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TemplateScriptMapper {

    ScriptResponseDto toDto(TemplateScript entity);

    List<ScriptResponseDto> toDtoList(List<TemplateScript> entities);
}
