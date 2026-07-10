package com.example.shared.command;

public interface Command<T> {
    CommandResult<T> execute();
}
