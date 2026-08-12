package com.example.editor.model.component;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "component_property",
        schema = "editor",
        // Имя строки — её адрес: по нему значение набора находит строку, а writeTag —
        // свойство. Проверка есть и в коде, но она одна отделяла дубль от данных (scada-95o).
        uniqueConstraints = @UniqueConstraint(
                name = "component_property_uk",
                columnNames = {"component_id", "name"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComponentProperty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;

    /**
     * Порядковый номер свойства — <b>только для представления</b>: первая колонка таблицы и
     * порядок строк/полей в редакторе. Ключом привязки чего-либо к строке служить не может:
     * при вставке строки в середину номера сдвигаются, и ссылка по номеру начинает указывать
     * на соседнюю строку. Значения наборов привязаны к строке по {@code name}
     * (см. {@code RecipeValue}).
     * <p>
     * Nullable: у свойств, созданных до появления поля, номера нет — при сортировке они уходят
     * в конец (в Postgres {@code ORDER BY ... ASC} даёт NULLS LAST).
     */
    private Integer position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private Component component;

    private String tagId;

    @Column(nullable = false)
    private String propertyType;

    private String description;

    @Column(nullable = false)
    private String valueType;

    private String defaultValue;

    @Column(nullable = false)
    private Boolean logging = false;

    @Column(columnDefinition = "text")
    private String onChange;
}
