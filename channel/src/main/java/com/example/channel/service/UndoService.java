package com.example.channel.service;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandLogRepository;
import com.example.channel.config.command.CommandManager;
import com.example.channel.config.command.UndoHandler;
import com.example.channel.exception.NotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class UndoService {

    private final CommandLogRepository commandLogRepository;
    private final CommandManager commandManager;
    private final List<UndoHandler> handlers;

    public UndoService(
            CommandLogRepository commandLogRepository,
            CommandManager commandManager,
            List<UndoHandler> handlers
    ) {
        this.commandLogRepository = commandLogRepository;
        this.commandManager = commandManager;
        this.handlers = handlers;
    }


    public List<CommandLog> getLogsByPeriod(LocalDateTime from, LocalDateTime to) {
        return commandLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
    }

    public List<CommandLog> getLogsByBatchId(UUID batchId) {
        return commandLogRepository.findByBatchIdAndUndoneAtIsNullOrderBySequenceDescIdDesc(batchId);
    }

    /**
     * Undoes all non-undone logs in a batch atomically (fail-fast, marks batch as undone on success).
     */
    @Transactional
    public void undoBatch(UUID batchId, String userName) {
        List<CommandLog> logs = commandLogRepository
                .findByBatchIdAndUndoneAtIsNullOrderBySequenceDescIdDesc(batchId);

        if (logs.isEmpty()) {
            throw new NotFoundException("Batch not found or already undone: " + batchId);
        }

        undoLogsAndMark(logs, userName);
    }

    /**
     * Undoes specific log entries (legacy). Continues on per-log failure; marks successfully undone logs.
     */
    @Transactional
    public List<Long> undoLogs(List<Long> commandLogIds, String userName) {
        List<Long> failedIds = new ArrayList<>();

        List<CommandLog> logs = commandLogRepository.findAllById(commandLogIds)
                .stream()
                .sorted(Comparator.comparing(CommandLog::getId).reversed())
                .toList();

        for (CommandLog log : logs) {
            try {
                undoSingleLog(log, userName);
                markUndone(log);
            } catch (Exception e) {
                failedIds.add(log.getId());
                System.err.println(
                        "Undo failed for logId=" + log.getId() + " : " + e.getMessage()
                );
            }
        }

        return failedIds;
    }

    private void undoLogsAndMark(List<CommandLog> logs, String userName) {
        for (CommandLog log : logs) {
            undoSingleLog(log, userName);
        }

        LocalDateTime undoneAt = LocalDateTime.now();
        for (CommandLog log : logs) {
            markUndone(log, undoneAt);
        }
    }

    private void undoSingleLog(CommandLog log, String userName) {
        if (log.getUndoneAt() != null) {
            throw new IllegalStateException("Log already undone: " + log.getId());
        }

        UndoHandler handler = handlers.stream()
                .filter(h -> h.supports(log.getCommandType()))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "No UndoHandler for commandType " + log.getCommandType()
                        )
                );

        commandManager.executeUndo(handler, log, userName);
    }

    private void markUndone(CommandLog log) {
        markUndone(log, LocalDateTime.now());
    }

    private void markUndone(CommandLog log, LocalDateTime undoneAt) {
        log.setUndoneAt(undoneAt);
        commandLogRepository.save(log);
    }
}
