package com.example.runtime.automation;

import com.example.runtime.automation.engine.CommandSender;
import com.example.runtime.kafka.CommandOutcome;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.kafka.PendingCommandRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Команды фоновых задач идут тем же путём, что команды мониторов и процедур: один producer,
 * одно ожидание результатов. Раньше у сервиса automation был свой CommandGateway.
 */
@Component
@RequiredArgsConstructor
public class RuntimeCommandSender implements CommandSender {

    private final CommandProducer producer;
    private final PendingCommandRegistry pending;

    @Override
    public CompletableFuture<CommandOutcome> send(String tag, Object value) {
        return producer.send(tag, value)
                .exceptionally(e -> CommandOutcome.failure(CommandOutcome.NOT_DELIVERED,
                        "Команда не отправлена: " + e.getMessage()));
    }

    @Override
    public long lastResultAtMs() {
        return pending.lastSettledAtMs();
    }
}
