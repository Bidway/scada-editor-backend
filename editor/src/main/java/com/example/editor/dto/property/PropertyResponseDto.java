package com.example.editor.dto.property;

import lombok.Data;

@Data
public class PropertyResponseDto {
    private Long id;
    private String name;
    private Long component_id;
    private String property_type;
    private String tag_id;
    private String description;
    private String value_type;
    private String default_value;
    private boolean logging;
    // Сырой JS (см. PropertyCreateDto.onChange) — отдаётся runtime как есть.
    private String onChange;
}
