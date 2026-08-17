package com.example.channel.command;

public interface Command<T> {
    CommandResult<T> execute();
}
