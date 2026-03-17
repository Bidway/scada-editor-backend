package com.example.editor.dto.template;

import lombok.Data;

@Data
public class TemplateCreateDto {
    private String name;
    private String type;
    private TemplateComponentCreateDto rootComponent;
}
