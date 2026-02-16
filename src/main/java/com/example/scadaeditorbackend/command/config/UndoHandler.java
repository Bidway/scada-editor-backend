package com.example.scadaeditorbackend.command.config;

public interface UndoHandler {

    boolean supports(String commandType);

    CommandResult undo(CommandLog source);
}

