package com.example.editor.service;

import com.example.editor.config.command.CommandLog;
import com.example.editor.config.command.CommandLogRepository;
import com.example.editor.config.command.CommandManager;
import com.example.shared.command.UndoHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Выполняет undo одной записи журнала в отдельной транзакции ({@link Propagation#REQUIRES_NEW}).
 * <p>
 * Нужен для {@link UndoService#undoLogs}, где отмены обрабатываются независимо: сбой одной
 * не должен ронять остальные. Если бы все они шли в одной транзакции (а
 * {@link CommandManager#executeUndo} сам {@code @Transactional} и присоединяется к текущей),
 * то первое же исключение пометило бы транзакцию rollback-only, и уже сохранённые отмены
 * всё равно откатились бы на коммите с {@code UnexpectedRollbackException} — при этом
 * вызывающий по списку {@code failed} считал бы, что остальное прошло.
 * <p>
 * Это отдельный бин, а не приватный метод {@link UndoService}, потому что {@code REQUIRES_NEW}
 * работает только через прокси Spring — self-invocation его бы проигнорировал.
 */
@Component
@RequiredArgsConstructor
class UndoExecutor {

    private final CommandLogRepository commandLogRepository;
    private final CommandManager commandManager;
    private final List<UndoHandler<CommandLog>> handlers;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void undoOne(Long logId, String userName) {
        CommandLog log = commandLogRepository.findById(logId)
                .orElseThrow(() -> new IllegalStateException("Log not found: " + logId));
        if (log.getUndoneAt() != null) {
            throw new IllegalStateException("Log already undone: " + log.getId());
        }
        UndoHandler<CommandLog> handler = handlers.stream()
                .filter(h -> h.supports(log.getCommandType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No UndoHandler for commandType: " + log.getCommandType()));
        commandManager.executeUndo(handler, log, userName);
        log.setUndoneAt(LocalDateTime.now());
        commandLogRepository.save(log);
    }
}
