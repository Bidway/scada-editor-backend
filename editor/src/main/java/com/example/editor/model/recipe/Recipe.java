package com.example.editor.model.recipe;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Именованный набор значений для строк таблицы-компонента. Рецепт под конкретный продукт —
 * основной, но не единственный случай: тем же хранилищем описываются параметры станции,
 * пресеты режимов и т.п., их различает {@link #type}. Оператор выбирает набор и применяет —
 * значения уходят в теги → ПЛК, а строки без тега остаются локальными (см. runtime).
 * <p>
 * Привязка к таблице — плоским {@code componentId}, без FK: удаление таблицы не должно
 * блокироваться наборами, а осиротевший набор безвреден (его просто не выберут).
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

    /**
     * Назначение набора: {@code recipe} (рецепт под продукт), {@code station_params}
     * (параметры станции) и т.д. Не путать с {@code Component.type} — тот описывает вид
     * компонента ({@code project}/{@code scene}/{@code table}), этот — вид набора значений.
     */
    @Column(nullable = false)
    @Builder.Default
    private String type = RecipeTypes.RECIPE;

    /** id таблицы-компонента (type="table"), к которой относится набор. */
    @Column(name = "component_id", nullable = false)
    private Long componentId;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<RecipeValue> values = new ArrayList<>();
}
