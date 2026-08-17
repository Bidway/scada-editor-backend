package com.example.channel.service;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandLogRepository;
import com.example.channel.config.command.CommandManager;
import com.example.channel.command.UndoHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Выполняет отмену одной записи журнала в отдельной транзакции ({@link Propagation#REQUIRES_NEW}).
 * <p>
 * Нужен для {@link UndoService#undoLogs}, где записи обрабатываются независимо: сбой одной
 * не должен ронять остальные. Если бы все они шли в одной транзакции, первая же ошибка на
 * уровне БД пометила бы её rollback-only, и уже применённые отмены откатились бы на коммите
 * с {@code UnexpectedRollbackException} — при этом вызывающий по списку неудачных считал бы,
 * что остальное прошло (scada-6ua).
 * <p>
 * Это отдельный бин, а не приватный метод {@link UndoService}, потому что REQUIRES_NEW
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
                        "No UndoHandler for commandType " + log.getCommandType()));
        commandManager.executeUndo(handler, log, userName);
        log.setUndoneAt(LocalDateTime.now());
        commandLogRepository.save(log);
    }
}
