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
    volatile String lastValue;

    public TagRuntimeState(String tagId) {
        this.tagId = tagId;
    }
}
