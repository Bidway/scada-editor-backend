package com.example.editor.config.command;

import com.example.shared.command.Command;
import com.example.shared.command.CommandResult;
import com.example.shared.command.UndoHandler;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class CommandManager {

    private final CommandLogRepository commandRepository;

    public CommandManager(CommandLogRepository commandRepository) {
        this.commandRepository = commandRepository;
    }

    @Transactional
    public <T> T execute(Command<T> command) {
        CommandResult<T> result = command.execute();
        // Журнал пишется при любом результате: команда без имени пользователя всё равно
        // изменила данные, и без записи в command_log её нельзя отменить. Прежнее условие
        // молча теряло такие команды. Семантика приведена к channel (scada-2zq).
        if (result != null) {
            commandRepository.save(CommandLog.from(result));
        }
        return result != null ? result.getResult() : null;
    }

    @Transactional
    public void executeUndo(UndoHandler<CommandLog> handler, CommandLog log, String userName) {
        CommandResult<?> result = handler.undo(log, userName);
        if (result != null) {
            commandRepository.save(CommandLog.from(result));
        }
    }
}
