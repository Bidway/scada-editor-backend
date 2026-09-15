package com.example.editor.model.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Таблица-справочник проекта: читается серверными скриптами через {@code data()}.
 * <p>
 * FK на проект нет по той же причине, что у {@code AutomationTask}: проект удаляется
 * {@code deleteById} в обход графа. Строки убирает {@code ProjectDataService.onProjectDeleted}.
 * <p>
 * UNIQUE на {@code (project_id, name)} безопасен, в отличие от задач автоматизации: набор
 * сопоставляется по имени, поэтому вставки имени удаляемой таблицы не бывает.
 */
@Entity
@Table(name = "project_data_table", schema = "editor",
        uniqueConstraints = @UniqueConstraint(name = "project_data_table_uk", columnNames = {"project_id", "name"}),
        indexes = @Index(name = "project_data_table_project_idx", columnList = "project_id"))
@Getter
@Setter
public class ProjectDataTable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private String name;

    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** {@code [{name, title, value_type, required, default_value}]} */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode columns;

    /** {@code [{key, values: {<колонка>: значение}}]} */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode rows;
}
