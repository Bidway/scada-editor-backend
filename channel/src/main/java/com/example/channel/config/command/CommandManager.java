package com.example.channel.config.command;

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
    /**
     * Выполняет команду и пишет её в журнал. Возвращает результат, а не обёртку:
     * CommandResult нужен только на участке между Command и журналом (scada-2zq).
     */
    @Transactional
    public <T> T execute(Command<T> command) {
        CommandResult<T> result = command.execute();
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
