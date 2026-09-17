package com.example.runtime.automation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Настройки фоновых задач проектов — бывший блок {@code automation.engine} сервиса automation. */
@Component
@ConfigurationProperties(prefix = "runtime.automation")
@Getter
@Setter
public class AutomationEngineProperties {

    private int workerThreads = 4;
    private int contextPoolSize = 4;
    private long checkpointFlushMs = 1000;
    private long statusRefreshMs = 10000;
    /** Первая пауза повтора загрузки данных проекта; удваивается до {@link #dataRetryMaxMs}. */
    private long dataRetryMinMs = 5000;
    private long dataRetryMaxMs = 60000;
}
