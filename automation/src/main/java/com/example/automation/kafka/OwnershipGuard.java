package com.example.automation.kafka;

import com.example.automation.config.AutomationProperties;
import org.springframework.stereotype.Component;

/**
 * Самоограничение против «зомби»: владение действительно, только пока последний успешный опрос
 * Kafka был не раньше половины {@code session.timeout.ms}. Зависший экземпляр (долгая пауза GC),
 * у которого Kafka уже отобрала партиции, не отправит в ПЛК устаревших команд.
 */
@Component
public class OwnershipGuard {

    private final long maxSilenceMs;
    private volatile long lastPollAtMs;

    public OwnershipGuard(AutomationProperties properties) {
        this.maxSilenceMs = properties.getKafka().getSessionTimeoutMs() / 2L;
    }

    public void markPolled() {
        lastPollAtMs = System.currentTimeMillis();
    }

    public boolean valid() {
        return lastPollAtMs != 0 && System.currentTimeMillis() - lastPollAtMs < maxSilenceMs;
    }
}
