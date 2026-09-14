package com.example.editor.model.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Переменная проекта — производное значение, которого нет в ПЛК («авария: нет расхода», режим
 * регулятора). Для фронта адресуется как тег {@code @var.<name>}. Без FK на проект — по той же
 * причине, что у {@link AutomationTask}.
 */
@Entity
@Table(name = "automation_variable", schema = "editor",
        indexes = @Index(name = "automation_variable_project_idx", columnList = "project_id"))
@Getter
@Setter
public class AutomationVariable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private String name;

    /** {@code bool | int | float | string} */
    @Column(name = "value_type", nullable = false, length = 16)
    private String valueType;

    @Column(name = "default_value", columnDefinition = "text")
    private String defaultValue;

    @Column(columnDefinition = "text")
    private String description;
}
