package com.example.runtime.automation.engine;

import com.example.runtime.kafka.CommandOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * scada-cre: после подтверждённой записи тег переписал кто-то другой (экран). Кэш «уже
     * подтверждено» этого не знал, и задача молча переставала владеть выходом. Телеметрия,
     * пришедшая после подтверждения с другим значением, — повод записать своё снова.
     */
    @Test
    void foreignWriteAfterConfirmation_isOverwrittenOnNextTick() {
        List<Object> sent = new ArrayList<>();
        Map<String, TagReading> readings = new HashMap<>();
        long[] now = {1_000};
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
        }, readings::get, () -> now[0]);

        writer.write("PUMP.V", 0.0);
        // Телеметрия, снятая ещё до записи, — не повод: наш 0 уже подтверждён позже.
        readings.put("PUMP.V", new TagReading(20.0, true, 500));
        writer.write("PUMP.V", 0.0);
        assertEquals(List.of(0.0), sent);

        now[0] = 3_000;
        readings.put("PUMP.V", new TagReading(20.0, true, 2_500));
        writer.write("PUMP.V", 0.0);
        assertEquals(List.of(0.0, 0.0), sent);

        // Float32 из ПЛК с тем же значением — не чужая запись.
        readings.put("PUMP.V", new TagReading(64.69999694824219, true, 4_000));
        writer.write("PUMP.V", 64.7);
        now[0] = 5_000;
        readings.put("PUMP.V", new TagReading(64.69999694824219, true, 4_500));
        writer.write("PUMP.V", 64.7);
        assertEquals(List.of(0.0, 0.0, 64.7), sent);
    }
}
