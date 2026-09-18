package com.example.editor.dto.property;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    private Integer position;
    private boolean logging;
    // Сырой JS (см. PropertyCreateDto.onChange) — отдаётся runtime как есть.
    private String onChange;

    /**
     * Номер версии сцены, записанной этой правкой, — только в ответах эндпоинтов свойств
     * (scada-6e1). Внутри компонента и в снимках версий не сериализуется: null там всегда, а
     * лишнее поле поменяло бы хеш снимка.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer version_no;
}
