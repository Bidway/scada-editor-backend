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

import java.time.LocalDateTime;

/**
 * Очередь публикации определений в Kafka. Строка пишется в той же транзакции, что и сам набор,
 * поэтому сохранённое в БД не может не доехать до топика — в худшем случае доедет позже.
 * {@link #payload} {@code null} — tombstone (проект удалён).
 */
@Entity
@Table(name = "automation_outbox", schema = "editor",
        indexes = @Index(name = "automation_outbox_pending_idx", columnList = "published_at, id"))
@Getter
@Setter
public class AutomationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** Номер версии {@code document_version}; {@code null} у tombstone. */
    @Column(name = "definitions_version")
    private Integer definitionsVersion;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
