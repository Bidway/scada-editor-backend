package com.example.editor.dto.template;

import lombok.Data;

@Data
public class TemplateCreateDto {
    private String name;
    private String type;
    private TemplateComponentCreateDto rootComponent;

    /**
     * Версия, на которой основывался клиент. Обязательно, если у шаблона уже есть версии.
     * Обёртки-конверта здесь нет: тело шаблона и так объект, оборачивать нечего.
     */
    private Integer based_on_version;

    /** {@code MANUAL} либо {@code AUTOSAVE}. Не прислали — считаем ручным. */
    private String save_kind;
}
