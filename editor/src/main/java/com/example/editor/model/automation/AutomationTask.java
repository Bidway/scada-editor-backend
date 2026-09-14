package com.example.editor.model.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Фоновая задача проекта: исполняется сервисом {@code automation} по периоду, читает
 * {@link #inputs}, пишет {@link #outputs} и переменные проекта.
 * <p>
 * Внешнего ключа на проект нет намеренно: проект удаляется {@code deleteById} в обход графа
 * сущностей, и FK уронил бы удаление. Строки убирает {@code AutomationService.onProjectDeleted}.
 * <p>
 * UNIQUE на {@code (project_id, name)} тоже нет: набор сохраняется целиком, а Hibernate
 * вставляет раньше, чем удаляет, — переименование задачи в имя удалённой упало бы на ограничении.
 * Уникальность держит {@code AutomationSetValidator}.
 */
@Entity
@Table(name = "automation_task", schema = "editor",
        indexes = @Index(name = "automation_task_project_idx", columnList = "project_id"))
@Getter
@Setter
public class AutomationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "period_ms", nullable = false)
    private int periodMs;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @Column(name = "stale_after_ms", nullable = false)
    private int staleAfterMs;

    @Column(name = "run_on_stale", nullable = false)
    private boolean runOnStale;

    /** {@code [{alias, tag, value_type}]} */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode inputs;

    /** {@code [{alias, tag, value_type}]} */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode outputs;

    /** Имена переменных проекта, которые пишет задача. */
    @Type(JsonBinaryType.class)
    @Column(name = "writes_variables", columnDefinition = "jsonb", nullable = false)
    private JsonNode writesVariables;

    @Column(columnDefinition = "text", nullable = false)
    private String script;
}
