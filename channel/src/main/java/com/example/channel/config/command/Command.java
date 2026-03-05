package com.example.channel.config.command;

public interface Command<T> {
    CommandResult<T> execute();
}
