package com.example.editor.dto.automation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Вход или выход шаблона. {@code example_tag} — пример пути тега, подсказка инженеру: шаблон
 * ни к какому каналу не привязан, при вставке тег вводится заново.
 */
public record AutomationTemplateIoDto(
        String alias,
        @JsonProperty("example_tag") String exampleTag,
        @JsonProperty("value_type") String valueType) {
}
