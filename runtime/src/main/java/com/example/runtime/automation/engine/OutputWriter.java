package com.example.runtime.automation.engine;

import com.example.runtime.kafka.CommandOutcome;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Запись в теги ПЛК от задач проекта.
 * <ul>
 *   <li>Значение, равное последнему подтверждённому, не отправляется — если только после
 *       подтверждения тег не переписал кто-то другой (см. {@link #overwrittenSince}).</li>
 *   <li>На тег — не больше одной неразрешённой команды; новое значение заменяет ожидающее,
 *       а не встаёт в очередь: всегда пишется самое свежее.</li>
 * </ul>
 */
public final class OutputWriter {

    /** Float32 из ПЛК: 64.7 возвращается как 64.69999694824219 — это не чужая запись. */
    private static final double FLOAT32_TOLERANCE = 1e-4;

    private final CommandSender sender;
    private final TagReader tags;
    private final LongSupplier clock;
    private final Map<String, Object> confirmed = new HashMap<>();
    private final Map<String, Long> confirmedAtMs = new HashMap<>();
    private final Map<String, Object> inFlight = new HashMap<>();
    private final Map<String, Object> queued = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();

    /** Без чтения тегов: чужую запись не видит (для тестов, которым она не важна). */
    public OutputWriter(CommandSender sender) {
        this(sender, tag -> null, System::currentTimeMillis);
    }

    public OutputWriter(CommandSender sender, TagReader tags, LongSupplier clock) {
        this.sender = sender;
        this.tags = tags;
        this.clock = clock;
    }

    public synchronized void write(String tag, Object value) {
        if (inFlight.containsKey(tag)) {
            if (Objects.equals(inFlight.get(tag), value)) {
                queued.remove(tag);
            } else {
                queued.put(tag, value);
            }
            return;
        }
        if (confirmed.containsKey(tag) && Objects.equals(confirmed.get(tag), value)
                && !overwrittenSince(tag, value)) {
            return;
        }
        dispatch(tag, value);
    }

    /** Причина последнего отказа записи в тег (и сброс её); {@code null} — отказов не было. */
    public synchronized String takeFailure(String tag) {
        return failures.remove(tag);
    }

    /**
     * Тег переписали после нашего подтверждения: пришла телеметрия новее подтверждения, и в ней
     * не наше значение. Без этой проверки задача молча переставала владеть выходом — ручная
     * запись с экрана висела, пока расчёт давал прежнее значение (scada-cre). Телеметрия, снятая
     * до подтверждения, в счёт не идёт: она ещё не видела нашей записи.
     */
    private boolean overwrittenSince(String tag, Object value) {
        TagReading reading = tags.read(tag);
        Long since = confirmedAtMs.get(tag);
        if (reading == null || !reading.good() || since == null || reading.receivedAtMs() <= since) {
            return false;
        }
        return !sameValue(reading.value(), value);
    }

    private static boolean sameValue(Object actual, Object written) {
        if (actual instanceof Number a && written instanceof Number w) {
            double scale = Math.max(1.0, Math.abs(w.doubleValue()));
            return Math.abs(a.doubleValue() - w.doubleValue()) <= FLOAT32_TOLERANCE * scale;
        }
        return Objects.equals(String.valueOf(actual), String.valueOf(written));
    }

    private void dispatch(String tag, Object value) {
        inFlight.put(tag, value);
        sender.send(tag, value).whenComplete((outcome, error) -> onResult(tag, value, outcome));
    }

    private synchronized void onResult(String tag, Object value, CommandOutcome outcome) {
        inFlight.remove(tag);
        if (outcome != null && outcome.applied()) {
            confirmed.put(tag, value);
            confirmedAtMs.put(tag, clock.getAsLong());
        } else {
            // Исход неизвестен или отказ: подтверждённого значения больше нет, следующий такт пишет заново.
            confirmed.remove(tag);
            confirmedAtMs.remove(tag);
            failures.put(tag, outcome == null ? "нет исхода команды" : outcome.status() + ": " + outcome.message());
        }
        if (queued.containsKey(tag)) {
            dispatch(tag, queued.remove(tag));
        }
    }
}
