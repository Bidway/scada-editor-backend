package com.example.editor.dto;

import lombok.Data;

import java.util.List;

@Data
public class TemplateResponseDto {
    private Long id;
    private String name;
    private String type;
    private TemplateComponentResponseDto components;
}
