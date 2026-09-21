package com.example.runtime.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;

/**
 * Состояние идущей процедуры. Только активные: завершённая или прерванная строку удаляет,
 * журнала моек здесь нет. Ключ — (project_id, recipe_id): один и тот же рецепт нельзя
 * запустить в проекте дважды, это и отбивается 409 на повторный start.
 */
@Entity
@Table(name = "procedure_state", schema = "runtime",
        uniqueConstraints = @UniqueConstraint(name = "procedure_state_uk",
                columnNames = {"project_id", "recipe_id"}))
@Getter
@Setter
public class ProcedureStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "recipe_id", nullable = false)
    private String recipeId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    /** Время входа в шаг: из него восстанавливается elapsedMs после перезапуска. */
    @Column(name = "step_entered_at", nullable = false)
    private Instant stepEnteredAt;

    @Column(nullable = false)
    private boolean confirmed;

    /** Процедура стоит на шаге по аварии или по кнопке. default — для строк, записанных до колонки. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean paused;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "pause_reason", length = 500)
    private String pauseReason;

    /** Накопленное состояние тегов шагов 0..N — то, что применяет jump. */
    @Type(JsonBinaryType.class)
    @Column(name = "accumulated_actions", columnDefinition = "jsonb", nullable = false)
    private JsonNode accumulatedActions;

    @Column(name = "started_by")
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}
