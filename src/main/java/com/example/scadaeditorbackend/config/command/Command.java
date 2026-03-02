package com.example.scadaeditorbackend.config.command;

public interface Command<T> {
    CommandResult<T> execute();
}
