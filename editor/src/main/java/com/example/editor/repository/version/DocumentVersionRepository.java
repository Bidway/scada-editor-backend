package com.example.editor.repository.version;

import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    Optional<DocumentVersion> findTopByTargetTypeAndTargetIdOrderByVersionNoDesc(
            DocumentType targetType, Long targetId);

    List<DocumentVersion> findByTargetTypeAndTargetIdOrderByVersionNoDesc(
            DocumentType targetType, Long targetId);

    Optional<DocumentVersion> findByTargetTypeAndTargetIdAndVersionNo(
            DocumentType targetType, Long targetId, Integer versionNo);

    /** Версия, действовавшая на момент времени, — последняя, созданная не позже него. */
    Optional<DocumentVersion> findTopByTargetTypeAndTargetIdAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
            DocumentType targetType, Long targetId, LocalDateTime moment);

    List<DocumentVersion> findByKindAndCreatedAtLessThanOrderByCreatedAtAsc(
            VersionKind kind, LocalDateTime createdBefore);

    /**
     * Список версий документа с фильтрами. Границы периода обязательны: «не задано» вызывающий
     * заменяет крайними датами (см. {@code DocumentVersionService.list}).
     * <p>
     * Прежний вариант — {@code v.createdAt >= coalesce(:from, v.createdAt)} — был несаргируемым:
     * выражение над колонкой не даёт PostgreSQL свести диапазон к index range-scan по
     * {@code document_version_created_idx (target_type, target_id, created_at)}, и период
     * фильтровался постфактум (scada-qpd). Идиома {@code :from is null or ...} тоже не годится —
     * параметр, который встречается только в сравнении с {@code null}, PgJDBC не типизирует
     * («could not determine data type of parameter»). Прямое сравнение с параметром решает оба.
     * <p>
     * У {@code kinds} проверки на null нет намеренно: пустой или null список в {@code in}
     * биндить нельзя — Hibernate сгенерирует {@code in ()} и запрос упадёт. Вместо этого
     * вызывающий подставляет все значения перечисления, когда фильтр не задан.
     */
    @Query("""
            select v from DocumentVersion v
            where v.targetType = :targetType
              and v.targetId = :targetId
              and v.createdAt >= :from
              and v.createdAt <= :to
              and v.kind in :kinds
            order by v.versionNo desc
            """)
    List<DocumentVersion> findFiltered(
            @Param("targetType") DocumentType targetType,
            @Param("targetId") Long targetId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("kinds") List<VersionKind> kinds,
            Pageable pageable);
}
