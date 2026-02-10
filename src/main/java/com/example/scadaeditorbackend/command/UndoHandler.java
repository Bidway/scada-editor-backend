package com.example.scadaeditorbackend.command;

public interface UndoHandler {

    boolean supports(String commandType);

    CommandResult undo(CommandLog source);
}

