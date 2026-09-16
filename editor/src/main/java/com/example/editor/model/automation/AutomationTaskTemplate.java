package com.example.editor.model.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * Шаблон фоновой задачи: заготовка в палитре, из которой фронт собирает черновик
 * {@link AutomationTask}. Общий для всех проектов — {@code project_id} нет намеренно.
 * <p>
 * Вместо тега хранится {@code example_tag} — подсказка: связи с каналом у шаблона нет, при вставке
 * тег вводится заново. {@code enabled} тоже нет: задача из шаблона всегда создаётся выключенной.
 * <p>
 * UNIQUE на {@code name}, в отличие от задачи, поставить можно: шаблоны сохраняются по одному, и
 * порядок «вставка раньше удаления» из сохранения набора целиком здесь не мешает.
 */
@Entity
@Table(name = "automation_task_template", schema = "editor")
@Getter
@Setter
public class AutomationTaskTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column
    private String category;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "period_ms", nullable = false)
    private int periodMs;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @Column(name = "stale_after_ms", nullable = false)
    private int staleAfterMs;

    @Column(name = "run_on_stale", nullable = false)
    private boolean runOnStale;

    /** {@code [{alias, example_tag, value_type}]} */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode inputs;

    /** {@code [{alias, example_tag, value_type}]} */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode outputs;

    /** Имена переменных проекта — подсказка: переменные шаблон не создаёт. */
    @Type(JsonBinaryType.class)
    @Column(name = "writes_variables", columnDefinition = "jsonb", nullable = false)
    private JsonNode writesVariables;

    @Column(columnDefinition = "text", nullable = false)
    private String script;
}
