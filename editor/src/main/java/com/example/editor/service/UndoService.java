package com.example.editor.service;

import com.example.editor.config.command.CommandLog;
import com.example.editor.config.command.CommandLogRepository;
import com.example.editor.config.command.CommandManager;
import com.example.editor.config.command.UndoHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class UndoService {

    private final CommandLogRepository commandLogRepository;
    private final CommandManager commandManager;
    private final List<UndoHandler> handlers;

    public List<CommandLog> getLogsByPeriod(LocalDateTime from, LocalDateTime to) {
        return commandLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
    }

    @Transactional
    public void undoBatch(UUID batchId, String userName) {
        List<CommandLog> logs = commandLogRepository
                .findByBatchIdAndUndoneAtIsNullOrderBySequenceDescIdDesc(batchId);

        if (logs.isEmpty()) {
            throw new IllegalArgumentException("Batch not found or already undone: " + batchId);
        }

        LocalDateTime undoneAt = LocalDateTime.now();
        for (CommandLog log : logs) {
            undoSingle(log, userName);
            log.setUndoneAt(undoneAt);
            commandLogRepository.save(log);
        }
    }

    @Transactional
    public List<Long> undoLogs(List<Long> ids, String userName) {
        List<Long> failed = new ArrayList<>();
        List<CommandLog> logs = commandLogRepository.findAllById(ids)
                .stream()
                .sorted(Comparator.comparing(CommandLog::getId).reversed())
                .toList();

        for (CommandLog log : logs) {
            try {
                undoSingle(log, userName);
                log.setUndoneAt(LocalDateTime.now());
                commandLogRepository.save(log);
            } catch (Exception e) {
                failed.add(log.getId());
            }
        }
        return failed;
    }

    private void undoSingle(CommandLog log, String userName) {
        if (log.getUndoneAt() != null) {
            throw new IllegalStateException("Log already undone: " + log.getId());
        }
        UndoHandler handler = handlers.stream()
                .filter(h -> h.supports(log.getCommandType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No UndoHandler for commandType: " + log.getCommandType()));
        commandManager.executeUndo(handler, log, userName);
    }
}
