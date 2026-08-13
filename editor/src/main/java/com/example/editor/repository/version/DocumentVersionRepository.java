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
     * Список версий документа с необязательными фильтрами. Запрос один, а не четыре перегрузки:
     * {@code coalesce(:from, v.createdAt)} даёт планировщику один план и избавляет от
     * Specification ради трёх условий.
     * <p>
     * Границы периода собраны через {@code coalesce}, а не {@code :from is null or ...}: у
     * PostgreSQL параметр, который встречается только в сравнении с {@code null}, не имеет
     * типового контекста — драйвер падает с «could not determine data type of parameter».
     * {@code coalesce(:from, v.createdAt)} привязывает тип параметра к типу колонки, и при
     * {@code from = null} условие вырождается в {@code createdAt >= createdAt}, то есть в
     * «всегда истина» ({@code createdAt} — {@code NOT NULL}).
     * <p>
     * У {@code kinds} проверки на null нет намеренно: пустой или null список в {@code in}
     * биндить нельзя — Hibernate сгенерирует {@code in ()} и запрос упадёт. Вместо этого
     * вызывающий подставляет все значения перечисления, когда фильтр не задан (см.
     * {@code DocumentVersionService.list}).
     */
    @Query("""
            select v from DocumentVersion v
            where v.targetType = :targetType
              and v.targetId = :targetId
              and v.createdAt >= coalesce(:from, v.createdAt)
              and v.createdAt <= coalesce(:to, v.createdAt)
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
