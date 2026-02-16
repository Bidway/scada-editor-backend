package com.example.scadaeditorbackend.command.config;

public interface Command<T> {
    CommandResult<T> execute();
}
