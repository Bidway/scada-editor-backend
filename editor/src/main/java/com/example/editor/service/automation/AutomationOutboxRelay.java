package com.example.editor.service.automation;

import com.example.editor.model.automation.AutomationOutbox;
import com.example.editor.repository.automation.AutomationOutboxRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Доставка строк outbox в {@code automation.definitions}.
 * <p>
 * Изменения определений редки — месяцами их может не быть, поэтому база впустую не опрашивается:
 * публикация запускается сразу после коммита сохранения, а по таймеру только досылается то, что
 * не ушло (флаг {@link #pending}). На старте флаг поднят: после рестарта могли остаться неотправленные строки.
 * <p>
 * Порядок версий одного проекта соблюдается: если строка проекта не ушла, его более поздние строки
 * в этом проходе пропускаются. Всё на одном потоке — два прохода одновременно не идут.
 */
@Component
@Slf4j
public class AutomationOutboxRelay {

    private static final int BATCH = 100;

    private final AutomationOutboxRepository repository;
    private final AutomationDefinitionsPublisher publisher;
    private final AutomationTopicInitializer topicInitializer;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "automation-outbox");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean pending = true;

    public AutomationOutboxRelay(AutomationOutboxRepository repository,
                                 AutomationDefinitionsPublisher publisher,
                                 AutomationTopicInitializer topicInitializer,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.publisher = publisher;
        this.topicInitializer = topicInitializer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** После коммита, а не внутри транзакции: иначе строка ещё не видна, и откат сохранения опубликовал бы несуществующее. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnqueued(AutomationOutboxEnqueued event) {
        pending = true;
        executor.submit(this::drain);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        executor.submit(this::drain);
    }

    @Scheduled(fixedDelayString = "${editor.automation.outbox.retry-interval-ms:30000}")
    public void retry() {
        if (pending) {
            executor.submit(this::drain);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    void drain() {
        if (!topicInitializer.ensureTopic()) {
            pending = true;
            return;
        }
        try {
            BatchResult result;
            do {
                result = transactionTemplate.execute(status -> publishBatch());
            } while (result != null && result.full() && !result.failed());
            pending = result == null || result.failed();
        } catch (Exception e) {
            pending = true;
            log.warn("Automation outbox relay failed: {}", e.getMessage());
        }
    }

    private BatchResult publishBatch() {
        List<AutomationOutbox> rows = repository.lockPending(BATCH);
        Set<Long> blocked = new HashSet<>();
        for (AutomationOutbox row : rows) {
            if (blocked.contains(row.getProjectId())) {
                continue;
            }
            try {
                publisher.publish(row.getProjectId(), row.getPayload());
                row.setPublishedAt(LocalDateTime.now());
                row.setLastError(null);
            } catch (Exception e) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(String.valueOf(e.getMessage()));
                blocked.add(row.getProjectId());
                log.warn("Automation definitions of project {} (version {}) not published: {}",
                        row.getProjectId(), row.getDefinitionsVersion(), e.getMessage());
            }
        }
        return new BatchResult(!blocked.isEmpty(), rows.size() == BATCH);
    }

    private record BatchResult(boolean failed, boolean full) {
    }
}
