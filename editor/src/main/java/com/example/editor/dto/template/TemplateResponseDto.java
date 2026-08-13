package com.example.editor.dto.template;

import lombok.Data;

@Data
public class TemplateResponseDto {
    private Long id;
    private String name;
    private String type;
    private TemplateComponentResponseDto rootComponent;

    /** Номер версии, созданной этим сохранением. */
    private Integer version_no;
}
