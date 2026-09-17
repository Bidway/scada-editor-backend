package com.example.runtime.automation.engine;

import com.example.runtime.kafka.CommandOutcome;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Запись в теги ПЛК от задач проекта.
 * <ul>
 *   <li>Значение, равное последнему подтверждённому, не отправляется.</li>
 *   <li>На тег — не больше одной неразрешённой команды; новое значение заменяет ожидающее,
 *       а не встаёт в очередь: всегда пишется самое свежее.</li>
 * </ul>
 */
public final class OutputWriter {

    private final CommandSender sender;
    private final Map<String, Object> confirmed = new HashMap<>();
    private final Map<String, Object> inFlight = new HashMap<>();
    private final Map<String, Object> queued = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();

    public OutputWriter(CommandSender sender) {
        this.sender = sender;
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
        if (confirmed.containsKey(tag) && Objects.equals(confirmed.get(tag), value)) {
            return;
        }
        dispatch(tag, value);
    }

    /** Причина последнего отказа записи в тег (и сброс её); {@code null} — отказов не было. */
    public synchronized String takeFailure(String tag) {
        return failures.remove(tag);
    }

    private void dispatch(String tag, Object value) {
        inFlight.put(tag, value);
        sender.send(tag, value).whenComplete((outcome, error) -> onResult(tag, value, outcome));
    }

    private synchronized void onResult(String tag, Object value, CommandOutcome outcome) {
        inFlight.remove(tag);
        if (outcome != null && outcome.applied()) {
            confirmed.put(tag, value);
        } else {
            // Исход неизвестен или отказ: подтверждённого значения больше нет, следующий такт пишет заново.
            confirmed.remove(tag);
            failures.put(tag, outcome == null ? "нет исхода команды" : outcome.status() + ": " + outcome.message());
        }
        if (queued.containsKey(tag)) {
            dispatch(tag, queued.remove(tag));
        }
    }
}
