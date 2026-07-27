package com.example.editor.model.recipe;

import jakarta.persistence.*;
import lombok.*;

/**
 * Одно значение рецепта: «записать {@code value} в тег {@code tagId}». Ключ по {@code tagId}
 * (а не по id свойства) — применение это буквально запись в тег, и tagId стабилен к пересозданию
 * id свойств при пересохранении структуры таблицы. Описание строки для UI берётся join'ом
 * {@code tagId} → ComponentProperty таблицы.
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

    @Column(name = "tag_id", nullable = false)
    private String tagId;

    @Column(columnDefinition = "text")
    private String value;
}
