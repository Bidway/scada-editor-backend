package com.example.editor.model.recipe;

import jakarta.persistence.*;
import lombok.*;

/**
 * Одно значение набора: «строке {@code rowName} присвоить {@code value}».
 * <p>
 * Ключ — <b>имя строки</b>, а не id свойства, не тег и не номер. Тега у строки может не быть
 * вовсе (локальный параметр), и он меняется при перепривязке на другой адрес ПЛК; номер
 * сдвигается при вставке строки в середину, из-за чего значение молча ушло бы в соседнюю строку.
 * Имя переживает и то, и другое, уникально в пределах компонента и уже используется как адрес
 * свойства в скриптах ({@code writeTag}).
 * <p>
 * Прежде у имени был ещё один довод: {@code applyProperties} пересохранял таблицу через
 * clear + insert, и id всех строк при этом менялись. Сейчас строки сопоставляются по имени и
 * переиспользуются, id стабильны — но ключом остаётся имя: оно единственное, что осмысленно и
 * для строки без тега, и для набора, составленного до того, как строка получила id.
 * <p>
 * Тег и тип значения строки добираются join'ом {@code rowName} → ComponentProperty при резолве.
 */
@Entity
@Table(name = "recipe_value", schema = "editor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipeValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @Column(name = "row_name", nullable = false)
    private String rowName;

    @Column(columnDefinition = "text")
    private String value;
}
