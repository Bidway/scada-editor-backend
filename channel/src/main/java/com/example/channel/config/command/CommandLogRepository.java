package com.example.channel.config.command;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommandLogRepository extends JpaRepository<CommandLog, Long> {
    Optional<CommandLog> findById(Long id);
    List<CommandLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime from,
            LocalDateTime to
    );
    List<CommandLog> findByBatchIdAndUndoneAtIsNullOrderBySequenceDescIdDesc(UUID batchId);

    /**
     * Запись под блокировкой строки — для отмены. Без неё два параллельных запроса на отмену
     * одной записи оба проходили проверку undoneAt и оба применяли снимок (scada-wjd).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from CommandLog l where l.id = :id")
    Optional<CommandLog> findByIdForUpdate(@Param("id") Long id);

    /** То же для отмены пакета: вторая транзакция дождётся первой и уже отменённых строк не увидит. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from CommandLog l where l.batchId = :batchId and l.undoneAt is null"
            + " order by l.sequence desc, l.id desc")
    List<CommandLog> findByBatchIdForUpdate(@Param("batchId") UUID batchId);
}
