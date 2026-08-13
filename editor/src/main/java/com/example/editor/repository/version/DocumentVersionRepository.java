package com.example.editor.repository.version;

import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
