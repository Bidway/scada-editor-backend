package com.example.runtime.kafka;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Общее для всех сессий состояние одного тега. {@code tagId} — путь узла базы каналов
 * (ComponentProperty.tagId в терминах editor), он же Kafka-key, по которому это состояние
 * обновляется из единого топика проекта: один узел = одно живое значение.
 */
public class TagRuntimeState {

    final String tagId;
    final Set<String> sessionIds = ConcurrentHashMap.newKeySet();

    /**
     * Значение, его достоверность и время снятия — одним снимком.
     * <p>
     * Тремя отдельными volatile-полями это читалось бы неатомарно: сессия, забирающая
     * состояние в момент обновления, могла бы получить значение до записи, а качество —
     * после. Для мнемосхемы такая пара означала бы «достоверно» поверх устаревшего
     * значения, то есть ровно ту ложь, ради устранения которой заводится качество.
     */
    volatile Snapshot snapshot = Snapshot.UNKNOWN;

    public TagRuntimeState(String tagId) {
        this.tagId = tagId;
    }

    /**
     * @param value последнее <b>достоверное</b> значение (строкой), {@code null} — его ещё не было
     * @param good  актуально ли {@code value} прямо сейчас
     * @param ts    момент снятия {@code value} с контроллера (epoch ms), {@code 0} — значения не было
     */
    public record Snapshot(String value, boolean good, long ts) {

        /** Тег известен (кто-то на него подписан), но телеметрия по нему ещё не приходила. */
        static final Snapshot UNKNOWN = new Snapshot(null, false, 0L);

        /**
         * Та же пара {@code value}/{@code ts}, но помеченная как недостоверная.
         * <p>
         * Значение намеренно не затирается: оператору полезнее видеть последнее известное
         * состояние с пометкой «связь потеряна», чем пустое место. Метка времени тоже
         * остаётся от него — иначе {@code now - ts} показывал бы возраст не значения, а
         * последней неудачной попытки чтения.
         */
        Snapshot asBad() {
            return good ? new Snapshot(value, false, ts) : this;
        }
    }
}
