package com.example.automation.engine;

import com.example.automation.definition.WatchdogDefinition;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Сторожевой таймер проекта: счётчик 0…65535 по кругу в тег ПЛК. Растёт, только пока владение
 * действительно, планировщик проекта жив (этот метод вообще вызывается) и шлюз отвечает на
 * команды не позже {@code 3 × period_ms}. Первые три периода после старта ответа не ждём —
 * иначе счётчик не сдвинулся бы никогда: его собственная запись и есть первая команда.
 * Ошибки скриптов счётчик не останавливают — они видны в статусе задачи.
 */
public final class WatchdogRunner {

    private final WatchdogDefinition definition;
    private final CommandSender sender;
    private final BooleanSupplier ownershipValid;
    private final LongSupplier clock;
    private final long startedAtMs;
    private int counter;

    public WatchdogRunner(WatchdogDefinition definition, CommandSender sender, BooleanSupplier ownershipValid,
                          LongSupplier clock) {
        this.definition = definition;
        this.sender = sender;
        this.ownershipValid = ownershipValid;
        this.clock = clock;
        this.startedAtMs = clock.getAsLong();
    }

    public long periodMs() {
        return definition.periodMs() > 0 ? definition.periodMs() : 1000;
    }

    public synchronized void tick() {
        long now = clock.getAsLong();
        if (!ownershipValid.getAsBoolean()) {
            return;
        }
        long window = 3 * periodMs();
        boolean gatewayAlive = now - startedAtMs < window || now - sender.lastResultAtMs() < window;
        if (!gatewayAlive) {
            return;
        }
        counter = (counter + 1) % 65536;
        sender.send(definition.tag(), (long) counter);
    }
}
