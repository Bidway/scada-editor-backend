package com.example.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "runtime")
public class RuntimeProperties {

    private String editorBaseUrl;
    private long flushIntervalMs = 40L;
    private final Script script = new Script();

    public String getEditorBaseUrl() {
        return editorBaseUrl;
    }

    public void setEditorBaseUrl(String editorBaseUrl) {
        this.editorBaseUrl = editorBaseUrl;
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public Script getScript() {
        return script;
    }

    public static class Script {
        private int contextPoolSize = 4;
        private long executionTimeoutMs = 200L;

        public int getContextPoolSize() {
            return contextPoolSize;
        }

        public void setContextPoolSize(int contextPoolSize) {
            this.contextPoolSize = contextPoolSize;
        }

        public long getExecutionTimeoutMs() {
            return executionTimeoutMs;
        }

        public void setExecutionTimeoutMs(long executionTimeoutMs) {
            this.executionTimeoutMs = executionTimeoutMs;
        }
    }
}
