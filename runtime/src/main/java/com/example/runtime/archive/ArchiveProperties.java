package com.example.runtime.archive;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Настройки архива тегов и журнала действий — {@code runtime.archive.*}. */
@Component
@ConfigurationProperties(prefix = "runtime.archive")
@Getter
@Setter
public class ArchiveProperties {

    /** false — не писать архив и журнал (чтение работает). */
    private boolean enabled = true;
    private int retentionDays = 30;
    private int queueCapacity = 200_000;
    private int batchSize = 5000;
    private long flushIntervalMs = 1000;
    private int maxTagsPerRequest = 20;
    /** Верхняя граница maxPoints запроса тренда. */
    private int maxPoints = 5000;
    private int replayPageSize = 50_000;
    private int maxReplayTags = 5000;
}
