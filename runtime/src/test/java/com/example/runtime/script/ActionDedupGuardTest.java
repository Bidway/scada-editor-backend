package com.example.runtime.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** scada-au4. Границы окна дедупа — через управляемый {@link Clock}, не {@code Thread.sleep}. */
class ActionDedupGuardTest {

    private static final long MIN_INTERVAL_MS = 300L;

    /** Часы, которые можно двигать вручную из теста. */
    private static Clock mutableClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    @Test
    @DisplayName("первый вызов для ключа всегда разрешён")
    void firstCallForKeyIsAllowed() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-31T10:00:00Z"));
        ActionDedupGuard guard = new ActionDedupGuard(MIN_INTERVAL_MS, mutableClock(now));

        assertThat(guard.allow("session-1:42")).isTrue();
    }

    @Test
    @DisplayName("повтор того же ключа раньше окна — отказ")
    void repeatBeforeWindowIsDenied() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-31T10:00:00Z"));
        ActionDedupGuard guard = new ActionDedupGuard(MIN_INTERVAL_MS, mutableClock(now));

        assertThat(guard.allow("session-1:42")).isTrue();
        now.set(now.get().plusMillis(MIN_INTERVAL_MS - 1));
        assertThat(guard.allow("session-1:42")).isFalse();
    }

    @Test
    @DisplayName("повтор того же ключа ровно на границе окна — уже разрешён")
    void repeatAtWindowBoundaryIsAllowed() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-31T10:00:00Z"));
        ActionDedupGuard guard = new ActionDedupGuard(MIN_INTERVAL_MS, mutableClock(now));

        assertThat(guard.allow("session-1:42")).isTrue();
        now.set(now.get().plusMillis(MIN_INTERVAL_MS));
        assertThat(guard.allow("session-1:42")).isTrue();
    }

    @Test
    @DisplayName("разные ключи не мешают друг другу")
    void differentKeysAreIndependent() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-31T10:00:00Z"));
        ActionDedupGuard guard = new ActionDedupGuard(MIN_INTERVAL_MS, mutableClock(now));

        assertThat(guard.allow("session-1:42")).isTrue();
        assertThat(guard.allow("session-1:43")).isTrue();
        assertThat(guard.allow("session-2:42")).isTrue();
    }

    @Test
    @DisplayName("после отказа окно не сдвигается — второй ранний повтор тоже отказ, а не сброс отсчёта")
    void deniedCallDoesNotResetTheWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-31T10:00:00Z"));
        ActionDedupGuard guard = new ActionDedupGuard(MIN_INTERVAL_MS, mutableClock(now));

        assertThat(guard.allow("session-1:42")).isTrue();
        now.set(now.get().plusMillis(100));
        assertThat(guard.allow("session-1:42")).isFalse();
        now.set(now.get().plusMillis(100)); // суммарно +200мс от первого разрешённого — всё ещё внутри окна
        assertThat(guard.allow("session-1:42")).isFalse();
        now.set(now.get().plusMillis(101)); // суммарно +301мс — окно вышло
        assertThat(guard.allow("session-1:42")).isTrue();
    }
}
