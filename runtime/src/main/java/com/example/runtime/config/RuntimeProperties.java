package com.example.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "runtime")
public class RuntimeProperties {

    private String editorBaseUrl;
    private long flushIntervalMs = 40L;

    /**
     * Потоки отправки WS-кадров (см. {@code OutboundFlusher}). Отправка блокирующая и
     * упирается в сеть клиента, поэтому потоков нужно больше, чем ядер: медленный
     * клиент занимает поток, но не должен задерживать остальные сессии.
     */
    private int flushThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
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

    public int getFlushThreads() {
        return flushThreads;
    }

    public void setFlushThreads(int flushThreads) {
        this.flushThreads = flushThreads;
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
        /**
         * Требовать на WS-handshake личность, проверенную gateway (заголовок {@code X-Username}).
         * Отключается только для локального прогона runtime без gateway.
         */
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
         * Сколько temporary-контекстов сверх пула допускается одновременно живыми при
         * устойчивой перегрузке (scada-3pz). Свыше этого — отказ вместо неограниченного
         * роста числа Graal-контекстов (каждый — сотни МБ на прогретый JS-движок).
         */
        private int maxOverflowContexts = 4;

        /**
         * Маленький резерв контекстов, который видит только ACTION — не общий пул (scada-8cp).
         * ACTION редки по сравнению с onChange, поэтому отдельный полноценный пул под них не
         * оправдан (простаивал бы и жёг память); этого резерва достаточно, чтобы ACTION не
         * голодал даже при полном захвате общего пула всплеском onChange. onChange в резерв
         * не заглядывает вовсе — только общий пул и overflow, как раньше.
         */
        private int actionReservePoolSize = 2;

        /**
         * Минимальный интервал между двумя ACTION с одним и тем же (sessionId, scriptId),
         * см. {@code ActionDedupGuard} (scada-au4). Гасит дребезг клика и повторную отправку
         * с фронта — повтор раньше этого интервала молча отбрасывается, не доходя до скрипта.
         */
        private long actionMinIntervalMs = 300L;

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

        public int getMaxOverflowContexts() {
            return maxOverflowContexts;
        }

        public void setMaxOverflowContexts(int maxOverflowContexts) {
            this.maxOverflowContexts = maxOverflowContexts;
        }

        public int getActionReservePoolSize() {
            return actionReservePoolSize;
        }

        public void setActionReservePoolSize(int actionReservePoolSize) {
            this.actionReservePoolSize = actionReservePoolSize;
        }

        public long getActionMinIntervalMs() {
            return actionMinIntervalMs;
        }

        public void setActionMinIntervalMs(long actionMinIntervalMs) {
            this.actionMinIntervalMs = actionMinIntervalMs;
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
