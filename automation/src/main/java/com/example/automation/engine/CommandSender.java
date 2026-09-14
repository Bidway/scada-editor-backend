package com.example.automation.engine;

import com.example.automation.command.CommandOutcome;

import java.util.concurrent.CompletableFuture;

public interface CommandSender {

    /** Future завершается ответом шлюза или NO_CONFIRMATION по таймауту; исключением — никогда. */
    CompletableFuture<CommandOutcome> send(String tag, Object value);

    /** Когда шлюз последний раз ответил на команду этого экземпляра, epoch ms; 0 — ещё не отвечал. */
    long lastResultAtMs();
}
