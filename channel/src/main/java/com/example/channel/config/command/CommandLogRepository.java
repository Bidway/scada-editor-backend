package com.example.channel.config.command;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
