package com.example.editor.dto.component;

import lombok.Data;

/**
 * Привязка свойства к выражению/скрипту. Свойство должно принадлежать тому же компоненту.
 * <p>
 * Адресуется одним из двух способов, id приоритетнее:
 * <ul>
 *   <li>{@code component_property_id} — обычный случай, свойство уже существует;</li>
 *   <li>{@code component_property_name} — строка создаётся <b>этим же</b> запросом и id у неё
 *       ещё нет. Имя и так служит ключом строки везде (значения наборов, {@code writeTag}),
 *       поэтому второго адреса тут не заводится — используется тот же.</li>
 * </ul>
 */
@Data
public class BindingPayloadDto {
    /**
     * id существующей строки. {@code null} означает «сущность новая» — это значение, а не
     * пропуск: у объекта, которого ещё нет в базе, id взяться неоткуда. Прислали id —
     * сопоставляем по нему, и переименование остаётся переименованием.
     */
    private Long id;
    private Long component_property_id;
    /** Альтернатива id: имя свойства в пределах того же компонента. */
    private String component_property_name;
    private String name;
    private String script;
}
