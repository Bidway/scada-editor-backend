package com.example.runtime.archive;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Секции архива и журнала по суткам: создаёт на сегодня и завтра, удаляет старше глубины
 * хранения целиком ({@code DROP TABLE}), без тяжёлых {@code DELETE}.
 * <p>
 * Первый проход — в {@code @PostConstruct}, до старта писателя и потребителей Kafka: иначе первые
 * точки суток без секции ушли бы в DEFAULT. Если это всё же случилось (runtime простоял дольше
 * суток, перевели часы), секция собирается «лечением»: отдельная таблица, перенос строк из
 * DEFAULT, ATTACH. Простой {@code CREATE … PARTITION OF} на такой диапазон Postgres отвергает —
 * и без лечения все следующие сутки навсегда оседали бы в DEFAULT, а очистка вставала.
 * <p>
 * Каждая операция — отдельно: сбой одной таблицы или суток не откатывает остальные. Экземпляров
 * runtime несколько, база одна: проход идёт под сессионной advisory-блокировкой — один делает,
 * остальные пропускают.
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
    private final ArchiveProperties props;
    private final Clock clock;

    @Autowired
    public ArchivePartitionManager(JdbcTemplate jdbc, ArchiveProperties props) {
        this(jdbc, props, Clock.systemDefaultZone());
    }

    /** Для тестов: часы и пояс, по которым считаются сутки. */
    public ArchivePartitionManager(JdbcTemplate jdbc, ArchiveProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    public void onStart() {
        maintain();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void maintain() {
        // Своё соединение, а не привязанное к транзакции вызывающего: лечение секции само
        // коммитит, а advisory-блокировка — сессионная.
        try (Connection c = jdbc.getDataSource().getConnection()) {
            c.setAutoCommit(true);
            if (tryLock(c)) {
                try {
                    LocalDate today = LocalDate.now(clock);
                    LocalDate oldest = today.minusDays(props.getRetentionDays());
                    for (String table : TABLES) {
                        ensure(c, table, today);
                        ensure(c, table, today.plusDays(1));
                        dropOlderThan(c, table, oldest);
                        long orphans = defaultRows(table);
                        if (orphans > 0) {
                            log.warn("runtime.{}_default holds {} rows outside daily partitions", table, orphans);
                        }
                    }
                } finally {
                    unlock(c);
                }
            }
        } catch (Exception e) {
            log.warn("Archive partition maintenance failed: {}", e.getMessage());
        }
    }

    private void ensure(Connection c, String table, LocalDate day) {
        String suffix = day.format(SUFFIX);
        if (partitions(table).contains(suffix)) {
            return;
        }
        // Границы суток — в поясе часов сервиса; timestamptz в SQL-литерале со смещением.
        String from = day.atStartOfDay(clock.getZone()).toOffsetDateTime().toString();
        String to = day.plusDays(1).atStartOfDay(clock.getZone()).toOffsetDateTime().toString();
        String part = "runtime." + table + "_" + suffix;
        String range = "ts >= '" + from + "' AND ts < '" + to + "'";
        try {
            if (hasRows(c, "SELECT EXISTS (SELECT 1 FROM runtime." + table + "_default WHERE " + range + ")")) {
                heal(c, table, part, range, from, to);
                log.warn("Archive partition {} rebuilt from rows that had landed in runtime.{}_default", part, table);
            } else {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS " + part + " PARTITION OF runtime." + table
                            + " FOR VALUES FROM ('" + from + "') TO ('" + to + "')");
                }
            }
        } catch (SQLException e) {
            log.warn("Archive partition {} not created: {}", part, e.getMessage());
        }
    }

    /** Секция из строк DEFAULT: одной транзакцией — таблица, перенос, ATTACH. */
    private static void heal(Connection c, String table, String part, String range, String from, String to)
            throws SQLException {
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + part + " (LIKE runtime." + table + " INCLUDING DEFAULTS)");
            st.execute("INSERT INTO " + part + " SELECT * FROM runtime." + table + "_default WHERE " + range);
            st.execute("DELETE FROM runtime." + table + "_default WHERE " + range);
            st.execute("ALTER TABLE runtime." + table + " ATTACH PARTITION " + part
                    + " FOR VALUES FROM ('" + from + "') TO ('" + to + "')");
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private void dropOlderThan(Connection c, String table, LocalDate oldest) {
        for (String suffix : partitions(table)) {
            if (!LocalDate.parse(suffix, SUFFIX).isBefore(oldest)) {
                continue;
            }
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS runtime." + table + "_" + suffix);
                log.info("Archive partition runtime.{}_{} dropped (older than {} days)",
                        table, suffix, props.getRetentionDays());
            } catch (SQLException e) {
                log.warn("Archive partition runtime.{}_{} not dropped: {}", table, suffix, e.getMessage());
            }
        }
    }

    private static boolean tryLock(Connection c) throws SQLException {
        return hasRows(c, "SELECT pg_try_advisory_lock(" + LOCK_KEY + ")");
    }

    private static void unlock(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
        }
    }

    private static boolean hasRows(Connection c, String booleanQuery) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(booleanQuery)) {
            return rs.next() && rs.getBoolean(1);
        }
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
