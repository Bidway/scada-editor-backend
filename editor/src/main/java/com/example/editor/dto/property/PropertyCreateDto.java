package com.example.editor.dto.property;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PropertyCreateDto {
    /**
     * id существующей строки. {@code null} означает «строка новая» — это значение, а не пропуск:
     * у объекта, которого ещё нет в базе, id взяться неоткуда. Прислали id — сопоставляем по
     * нему, и переименование остаётся переименованием, а не парой «удалили + создали».
     */
    private Long id;
    /**
     * Версия сцены, от которой отталкивался клиент. Обязательность определяет состояние
     * документа, а не HTTP-метод: у сцены есть версии — номер обязателен, нет — принимается
     * {@code null}. Правка свойства меняет сцену так же, как правка компонента, и обязана
     * проверяться тем же гардом (см. {@code DocumentVersionService.requireBase}).
     */
    private Integer based_on_version;
    private Long component_id;
    @NotBlank
    private String name;
    private String property_type;
    private String tag_id;
    private String description;
    private String value_type;
    private String default_value;
    // Номер строки/поля для представления. Не прислан — сервер проставит по позиции
    // в массиве properties[] (см. ComponentScriptBindingApplier.applyProperties).
    private Integer position;
    private boolean logging;
    // Сырой JS, исполняемый runtime при изменении привязанного тега (тот же формат,
    // что и Script компонента). Без JSON-обёртки — см. ScriptEngineService.
    private String onChange;
}
