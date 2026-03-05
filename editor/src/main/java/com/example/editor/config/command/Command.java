package com.example.editor.config.command;

public interface Command<T> {
    CommandResult<T> execute();
}
