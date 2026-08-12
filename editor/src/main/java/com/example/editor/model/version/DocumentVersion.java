package com.example.editor.model.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

/**
 * Снимок документа целиком на момент сохранения.
 * <p>
 * Одна таблица на сцены и шаблоны — отсюда и название {@code document_version}, а не
 * {@code scene_version}. Различает их {@link #targetType}, и он же говорит, как читать
 * {@link #content}.
 * <p>
 * Ссылок по внешнему ключу на сцену или шаблон нет намеренно: история обязана пережить удаление
 * документа — вопрос «кто удалил» задаётся уже после того, как документа нет. Та же причина, что
 * у {@code recipe_change}.
 * <p>
 * {@link #contentHash} — для дедупликации: содержимое совпало с предыдущей версией, значит
 * сохранять нечего. Инженер ушёл на два часа — в истории ноль пустых версий вместо восьми.
 */
@Entity
@Table(
        name = "document_version",
        schema = "editor",
        uniqueConstraints = @UniqueConstraint(
                name = "document_version_uk",
                columnNames = {"target_type", "target_id", "version_no"}),
        indexes = {
                @Index(name = "document_version_target_idx",
                        columnList = "target_type, target_id, version_no"),
                @Index(name = "document_version_created_idx",
                        columnList = "target_type, target_id, created_at")
        }
)
@Getter
@Setter
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private DocumentType targetType;

    /** id сцены или шаблона. Без FK: снимок переживает удаление документа. */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** Порядковый номер в пределах документа, с единицы. Не убывает никогда. */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionKind kind;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode content;

    /** SHA-256 от канонического JSON содержимого, hex. */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Версия, от которой отталкивался клиент. Заполнится планом 3b, пока всегда null. */
    @Column(name = "based_on_version")
    private Integer basedOnVersion;

    /** Для {@code kind = RESTORE}: номер восстановленной версии. */
    @Column(name = "restored_from")
    private Integer restoredFrom;
}
