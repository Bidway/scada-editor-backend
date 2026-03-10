package com.example.channel.config.command;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class CommandManager {
    private final CommandLogRepository commandRepository;

    public CommandManager(CommandLogRepository commandRepository) {
        this.commandRepository = commandRepository;
    }
//    @Transactional
//    public <T> T execute(Command<T> command){
//        CommandResult<T> result = command.execute();
//        if(result != null && result.getUserId()!=null)
//        commandRepository.save(CommandLog.from(result));
//        return result != null ? result.getResult() : null;
//    }
    @Transactional
    public <T> CommandResult<T> execute(Command<T> command) {
        CommandResult<T> result = command.execute(); // <-- здесь мы получаем CommandResult<T>
        if (result != null) {
            commandRepository.save(CommandLog.from(result));
        }
        return result; // <-- возвращаем CommandResult<T>
    }
    @Transactional
    public void executeUndo(UndoHandler handler, CommandLog log,Long userId){
        CommandResult result = handler.undo(log, userId);
        if(result != null)
            commandRepository.save(CommandLog.from(result));
    }
}
