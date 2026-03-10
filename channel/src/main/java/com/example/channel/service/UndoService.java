package com.example.channel.service;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandLogRepository;
import com.example.channel.config.command.CommandManager;
import com.example.channel.config.command.UndoHandler;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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

    @Transactional
    public List<Long> undo(List<Long> commandLogIds, Long userId) {

        List<Long> failedIds = new ArrayList<>();

        // Лучше делать undo в обратном порядке
        List<CommandLog> logs = commandLogRepository.findAllById(commandLogIds)
                .stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .toList();

        for (CommandLog log : logs) {

            try {

                UndoHandler handler = handlers.stream()
                        .filter(h -> h.supports(log.getCommandType()))
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException("No UndoHandler for commandType " + log.getCommandType())
                        );

                commandManager.executeUndo(handler, log, userId);

            } catch (Exception e) {

                failedIds.add(log.getId());

                // можно добавить лог
                System.err.println("Undo failed for logId=" + log.getId() + " : " + e.getMessage());
            }
        }

        return failedIds;
    }
}
