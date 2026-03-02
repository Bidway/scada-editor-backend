package com.example.scadaeditorbackend.channelbase.repository;

import com.example.scadaeditorbackend.config.command.CommandLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CommandLogRepository extends JpaRepository<CommandLog, Long> {
    Optional<CommandLog> findById(Long id);
}
