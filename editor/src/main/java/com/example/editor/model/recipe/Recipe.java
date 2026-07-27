package com.example.editor.model.recipe;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Рецепт — именованный набор уставок (значений) для строк таблицы-компонента (под конкретный
 * продукт). Оператор выбирает рецепт и применяет — значения пишутся в теги → ПЛК (см. runtime).
 * <p>
 * Привязка к таблице — плоским {@code componentId}, без FK: удаление таблицы не должно
 * блокироваться рецептами, а осиротевший рецепт безвреден (его просто не выберут).
 */
@Entity
@Table(name = "recipe", schema = "editor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** id таблицы-компонента (type="table"), к которой относится рецепт. */
    @Column(name = "component_id", nullable = false)
    private Long componentId;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RecipeValue> values = new ArrayList<>();
}
