package com.example.editor.dto.property;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PropertyCreateDto {
    private Long component_id;
    @NotBlank
    private String name;
    private String property_type;
    private String tag_id;
    private String description;
    private String value_type;
    private String default_value;
    private boolean logging;
    // Сырой JS, исполняемый runtime при изменении привязанного тега (тот же формат,
    // что и Script компонента). Без JSON-обёртки — см. ScriptEngineService.
    private String onChange;
}
