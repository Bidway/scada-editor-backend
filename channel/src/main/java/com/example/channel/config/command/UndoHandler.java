package com.example.channel.config.command;

public interface UndoHandler {

    boolean supports(String commandType);

    CommandResult undo(CommandLog log, String userName);
}

