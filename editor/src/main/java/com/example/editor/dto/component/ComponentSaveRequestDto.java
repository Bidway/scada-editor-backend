package com.example.editor.dto.component;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Тело сохранения сцены.
 * <p>
 * Метаданные лежат рядом с компонентами, а не в заголовках: ответ обязан быть объектом ради
 * блока {@code merged} (план 3b) — структуру в заголовок не положишь, — и запрос симметричен
 * ему. Числовой {@code based_on_version} в заголовке к тому же плохо читается в логах.
 */
@Getter
@Setter
public class ComponentSaveRequestDto {

    private List<ComponentCreateDto> components;

    /**
     * Версия, на которой основывался клиент. Обязательно, если у сцены уже есть версии;
     * при первом сохранении — {@code null}. Правило по состоянию документа, а не по
     * HTTP-методу: сохранение существующей сцены через POST иначе шло бы мимо проверки.
     */
    private Integer based_on_version;

    /** {@code MANUAL} либо {@code AUTOSAVE}. Не прислали — считаем ручным. */
    private String save_kind;
}
