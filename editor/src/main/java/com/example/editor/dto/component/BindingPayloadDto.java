package com.example.editor.dto.component;

import lombok.Data;

/**
 * Привязка свойства к выражению/скрипту. Свойство должно принадлежать тому же компоненту.
 */
@Data
public class BindingPayloadDto {
    private Long component_property_id;
    private String name;
    private String script;
}
