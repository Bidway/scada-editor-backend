package com.example.runtime.archive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Секции архива и журнала по суткам: создаёт на сегодня и завтра, удаляет старше глубины
 * хранения целиком ({@code DROP TABLE}), без тяжёлых {@code DELETE}.
 * <p>
 * Экземпляров runtime несколько, база одна: работа идёт под транзакционной advisory-блокировкой —
 * один делает, остальные пропускают. Сами операции идемпотентны ({@code IF [NOT] EXISTS}), так что
 * и без блокировки гонка была бы безопасной, блокировка лишь убирает двойную работу.
 */
@Component
@Slf4j
public class ArchivePartitionManager {

    public static final String TAG_ARCHIVE = "tag_archive";
    public static final String ACTION_LOG = "action_log";
    private static final List<String> TABLES = List.of(TAG_ARCHIVE, ACTION_LOG);
    private static final long LOCK_KEY = 0x7A_61_72_63_68L; // «zarch»
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ArchiveProperties props;
    private final Clock clock;

    @Autowired
    public ArchivePartitionManager(JdbcTemplate jdbc, PlatformTransactionManager txManager, ArchiveProperties props) {
        this(jdbc, txManager, props, Clock.systemDefaultZone());
    }

    /** Для тестов: часы и пояс, по которым считаются сутки. */
    public ArchivePartitionManager(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                   ArchiveProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        maintain();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void maintain() {
        try {
            tx.executeWithoutResult(status -> {
                Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(locked)) {
                    return;
                }
                LocalDate today = LocalDate.now(clock);
                LocalDate oldest = today.minusDays(props.getRetentionDays());
                for (String table : TABLES) {
                    create(table, today);
                    create(table, today.plusDays(1));
                    for (String suffix : partitions(table)) {
                        if (LocalDate.parse(suffix, SUFFIX).isBefore(oldest)) {
                            jdbc.execute("DROP TABLE IF EXISTS runtime." + table + "_" + suffix);
                            log.info("Archive partition runtime.{}_{} dropped (older than {} days)",
                                    table, suffix, props.getRetentionDays());
                        }
                    }
                    long orphans = defaultRows(table);
                    if (orphans > 0) {
                        log.warn("runtime.{}_default holds {} rows: a daily partition was missing when they were written",
                                table, orphans);
                    }
                }
            });
        } catch (Exception e) {
            log.warn("Archive partition maintenance failed: {}", e.getMessage());
        }
    }

    private void create(String table, LocalDate day) {
        // Границы суток — в поясе часов сервиса; timestamptz в SQL-литерале со смещением.
        String from = day.atStartOfDay(clock.getZone()).toOffsetDateTime().toString();
        String to = day.plusDays(1).atStartOfDay(clock.getZone()).toOffsetDateTime().toString();
        jdbc.execute("CREATE TABLE IF NOT EXISTS runtime." + table + "_" + day.format(SUFFIX)
                + " PARTITION OF runtime." + table + " FOR VALUES FROM ('" + from + "') TO ('" + to + "')");
    }

    /** Суффиксы {@code yyyyMMdd} суточных секций таблицы, по возрастанию (без DEFAULT). */
    public List<String> partitions(String table) {
        return jdbc.queryForList("""
                SELECT substring(c.relname FROM '_(\\d{8})$')
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                JOIN pg_namespace n ON n.oid = p.relnamespace
                WHERE n.nspname = 'runtime' AND p.relname = ? AND c.relname ~ '_\\d{8}$'
                ORDER BY 1
                """, String.class, table);
    }

    public long defaultRows(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM runtime." + table + "_default", Long.class);
        return n == null ? 0 : n;
    }
}
