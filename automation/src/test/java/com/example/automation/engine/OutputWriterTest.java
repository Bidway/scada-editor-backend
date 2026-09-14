package com.example.automation.engine;

import com.example.automation.command.CommandOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputWriterTest {

    /** Регулятор на установившемся режиме не заваливает шлюз одинаковыми командами. */
    @Test
    void sameConfirmedValueIsNotSentAgain() {
        List<Object> sent = new ArrayList<>();
        OutputWriter writer = new OutputWriter(new CommandSender() {
            @Override
            public CompletableFuture<CommandOutcome> send(String tag, Object value) {
                sent.add(value);
                return CompletableFuture.completedFuture(new CommandOutcome(true, "APPLIED", null));
            }

            @Override
            public long lastResultAtMs() {
                return 0;
            }
        });

        writer.write("PUMP.V", 40.0);
        writer.write("PUMP.V", 40.0);

        assertEquals(List.of(40.0), sent);
    }
}
