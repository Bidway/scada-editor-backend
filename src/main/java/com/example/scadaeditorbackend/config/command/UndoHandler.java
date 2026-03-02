package com.example.scadaeditorbackend.config.command;

public interface UndoHandler {

    boolean supports(String commandType);

    CommandResult undo(CommandLog source);
}

