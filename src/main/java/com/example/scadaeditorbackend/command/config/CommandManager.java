package com.example.scadaeditorbackend.command.config;

import com.example.scadaeditorbackend.repository.CommandLogRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class CommandManager {
    private final CommandLogRepository commandRepository;

    public CommandManager(CommandLogRepository commandRepository) {
        this.commandRepository = commandRepository;
    }
    @Transactional
    public <T> T execute(Command<T> command){
        CommandResult<T> result = command.execute();
        if(result != null)
        commandRepository.save(CommandLog.from(result));
        return result != null ? result.getResult() : null;
    }
    @Transactional
    public void executeUndo(UndoHandler handler, CommandLog source){
        CommandResult result = handler.undo(source);
        if(result != null)
            commandRepository.save(CommandLog.from(result));
    }
}
