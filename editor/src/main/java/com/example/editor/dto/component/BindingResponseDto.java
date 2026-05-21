package com.example.editor.dto.component;

import lombok.Data;

@Data
public class BindingResponseDto {
    private Long id;
    private Long component_property_id;
    private String name;
    private String script;
}
