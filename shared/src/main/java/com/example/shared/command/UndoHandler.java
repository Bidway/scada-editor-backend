package com.example.shared.command;

public interface UndoHandler {
    boolean supports(String commandType);
    CommandResult<?> undo(Object log, String userName);
}
