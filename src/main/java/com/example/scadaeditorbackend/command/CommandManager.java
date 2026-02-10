package com.example.scadaeditorbackend.command;

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
    public void execute(Command command){
        CommandResult result = command.execute();
        if(result != null)
        commandRepository.save(CommandLog.from(result));
    }
    @Transactional
    public void executeUndo(UndoHandler handler,CommandLog source){
        CommandResult result = handler.undo(source);
        if(result != null)
            commandRepository.save(CommandLog.from(result));
    }
}
