package com.example.editor.dto;

import lombok.Data;

import java.util.List;

@Data
public class TemplateCreateDto {
    private String name;
    private String type;
    private TemplateComponentCreateDto components;
}
