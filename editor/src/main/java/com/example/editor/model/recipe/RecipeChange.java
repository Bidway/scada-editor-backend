package com.example.editor.model.recipe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Одно изменение набора значений: правка значения строки, создание, переименование или
 * удаление набора.
 * <p>
 * Здесь нет связи на {@code Recipe} — только {@code recipeId} числом, как и у самого
 * {@code Recipe.componentId}. Это намеренно: история обязана пережить удаление набора, а
 * внешний ключ либо не дал бы его удалить, либо унёс бы историю следом. Ровно тот случай,
 * ради которого аудит и заводится: «кто удалил набор» — вопрос, на который нужно отвечать
 * после того, как набора уже нет.
 * <p>
 * {@code componentId} дублирует то, что можно было бы получить через {@code recipeId} →
 * набор → компонент: он позволяет отвечать на вопрос «что меняли по этому компоненту», не
 * собирая сначала список его наборов, и по той же причине, что и {@code recipeId}, переживает
 * удаление набора.
 * <p>
 * Снимок набора целиком не хранится: набор маленький, а спрашивают у него не «как выглядел»,
 * а «кто тронул эту строку».
 */
@Entity
@Table(name = "recipe_change")
@Getter
@Setter
public class RecipeChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipe_id", nullable = false)
    private Long recipeId;

    @Column(name = "component_id")
    private Long componentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 16)
    private RecipeChangeType changeType;

    /** Имя строки таблицы. Заполнено только для {@link RecipeChangeType#VALUE}. */
    @Column(name = "row_name")
    private String rowName;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    /** Кто. Приходит из заголовка X-Username, который проставляет gateway. */
    @Column(name = "user_name")
    private String userName;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt = LocalDateTime.now();
}
