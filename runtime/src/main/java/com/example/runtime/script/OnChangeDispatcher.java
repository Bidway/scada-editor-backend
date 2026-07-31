package com.example.runtime.script;

import com.example.runtime.config.RuntimeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Исполняет onChange-скрипты вне треда Kafka-consumer'а.
 * <p>
 * Раньше цепочка «poll → publishEvent → onMessage → runOnChange» была целиком
 * синхронной, потому что события Spring по умолчанию синхронны, а {@code @EnableAsync}
 * в приложении нет. Каждый скрипт блокировал приём телеметрии до 200 мс
 * ({@code runtime.script.execution-timeout-ms}), и при нескольких открытых сессиях на
 * один тег задержка складывалась. На заметном потоке это упирало consumer в
 * {@code max.poll.interval.ms}, вызывало ребаланс и лавинообразно роняло приём.
 * <p>
 * <b>Порядок.</b> Задачи одной сессии всегда попадают в один и тот же однопоточный
 * исполнитель: скрипты сессии читают и пишут {@code session.getPropertyValues()} и
 * сравнивают before/after, поэтому параллельный запуск двух скриптов одной сессии дал
 * бы гонку на этом состоянии. Разные сессии при этом идут параллельно.
 * <p>
 * <b>Перегрузка.</b> Очередь каждой полосы ограничена: при переполнении задача
 * отбрасывается с предупреждением, а не копится до OOM. Приём телеметрии важнее, чем
 * гарантия исполнения каждого onChange, — но потеря видна в логе и в счётчике, а не
 * происходит молча.
 */
@Component
@Slf4j
public class OnChangeDispatcher {

    /** Как часто печатать сводку об отброшенных задачах, чтобы не залить лог. */
    private static final long DROP_LOG_INTERVAL_MS = 10_000;

    private final RuntimeProperties properties;

    private ThreadPoolExecutor[] stripes;
    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastDropLogAt;

    public OnChangeDispatcher(RuntimeProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        int threads = properties.getScript().getOnChangeThreads();
        int queueCapacity = properties.getScript().getOnChangeQueueCapacity();
        stripes = new ThreadPoolExecutor[threads];
        for (int i = 0; i < threads; i++) {
            int index = i;
            stripes[i] = new ThreadPoolExecutor(
                    1, 1,
                    0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    r -> {
                        Thread t = new Thread(r, "onchange-" + index);
                        t.setDaemon(true);
                        return t;
                    },
                    (r, executor) -> registerDrop());
        }
        log.info("OnChangeDispatcher started: {} thread(s), queue capacity {} per thread",
                threads, queueCapacity);
    }

    @PreDestroy
    void shutdown() {
        if (stripes == null) {
            return;
        }
        for (ThreadPoolExecutor stripe : stripes) {
            stripe.shutdownNow();
        }
    }

    /**
     * Ставит исполнение скриптов в полосу, закреплённую за сессией.
     *
     * @param sessionId ключ закрепления — гарантирует последовательность в пределах сессии
     */
    public void submit(String sessionId, Runnable task) {
        stripeFor(sessionId).execute(task);
    }

    private ThreadPoolExecutor stripeFor(String sessionId) {
        // Math.floorMod, а не %: hashCode бывает отрицательным.
        return stripes[Math.floorMod(sessionId.hashCode(), stripes.length)];
    }

    private void registerDrop() {
        long total = dropped.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastDropLogAt >= DROP_LOG_INTERVAL_MS) {
            lastDropLogAt = now;
            log.warn("onChange scripts are falling behind: {} task(s) dropped so far. "
                    + "Increase runtime.script.on-change-threads or reduce script cost.", total);
        }
    }

    /** Сколько задач отброшено из-за перегрузки — для диагностики и метрик. */
    public long droppedCount() {
        return dropped.get();
    }
}
