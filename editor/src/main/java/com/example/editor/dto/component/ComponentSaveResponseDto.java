package com.example.editor.dto.component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Ответ сохранения и восстановления документа.
 * <p>
 * {@code components} объявлено как {@link JsonNode}, потому что тем же конвертом отвечает
 * восстановление шаблона: у него содержимое — дерево шаблона, а не список компонентов. Одна
 * форма на оба вида документа — это то, что обещает контракт фронту.
 * <p>
 * {@code restored_from} присутствует только у восстановления; у обычного сохранения его нет.
 */
@Getter
@Setter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComponentSaveResponseDto {

    private JsonNode components;

    private Integer version_no;

    private Integer restored_from;

    /**
     * Отчёт о подмешанных чужих правках. {@code null} у обычного сохранения — за это отвечает
     * {@code @JsonInclude(NON_NULL)} на классе.
     */
    private Map<String, Object> merged;

    public ComponentSaveResponseDto(JsonNode components, Integer version_no, Integer restored_from) {
        this(components, version_no, restored_from, null);
    }
}
