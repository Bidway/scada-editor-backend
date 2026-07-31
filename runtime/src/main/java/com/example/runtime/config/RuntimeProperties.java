package com.example.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "runtime")
public class RuntimeProperties {

    private String editorBaseUrl;
    private long flushIntervalMs = 40L;
    private final Script script = new Script();
    private final Session session = new Session();
    private final Ws ws = new Ws();

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

    public Session getSession() {
        return session;
    }

    public Ws getWs() {
        return ws;
    }

    public static class Ws {
        /** Требовать валидный JWT (query-параметр {@code token}) на WS-handshake. */
        private boolean requireAuth = true;

        public boolean isRequireAuth() {
            return requireAuth;
        }

        public void setRequireAuth(boolean requireAuth) {
            this.requireAuth = requireAuth;
        }
    }

    public static class Session {
        /** Через сколько минут закрывать сессию, к которой так и не подключился WebSocket. */
        private long abandonedTimeoutMinutes = 5L;

        public long getAbandonedTimeoutMinutes() {
            return abandonedTimeoutMinutes;
        }

        public void setAbandonedTimeoutMinutes(long abandonedTimeoutMinutes) {
            this.abandonedTimeoutMinutes = abandonedTimeoutMinutes;
        }
    }

    public static class Script {
        private int contextPoolSize = 4;
        private long executionTimeoutMs = 200L;

        /**
         * Число полос исполнения onChange (см. {@code OnChangeDispatcher}). Сессия
         * закрепляется за полосой, поэтому это же — предел параллелизма по сессиям.
         */
        private int onChangeThreads = Math.max(2, Runtime.getRuntime().availableProcessors());

        /** Глубина очереди одной полосы; при переполнении задача отбрасывается с warn. */
        private int onChangeQueueCapacity = 1000;

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

        public int getOnChangeThreads() {
            return onChangeThreads;
        }

        public void setOnChangeThreads(int onChangeThreads) {
            this.onChangeThreads = onChangeThreads;
        }

        public int getOnChangeQueueCapacity() {
            return onChangeQueueCapacity;
        }

        public void setOnChangeQueueCapacity(int onChangeQueueCapacity) {
            this.onChangeQueueCapacity = onChangeQueueCapacity;
        }
    }
}
