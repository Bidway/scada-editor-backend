package com.example.runtime.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArchiveWriterRetryTest {

    // Review Focus 3: БД легла на записи — пачка не теряется и после восстановления пишется ровно раз.
    @Test
    void пачка_при_ошибке_бд_сохраняется_и_пишется_один_раз_после_восстановления() {
        ArchiveProperties props = new ArchiveProperties();
        ArchiveRecorder recorder = new ArchiveRecorder(props, new ObjectMapper());
        ArchiveTagDictionary dictionary = mock(ArchiveTagDictionary.class);
        when(dictionary.ensure(any())).thenReturn(Map.of("a", 1));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicInteger written = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any()))
                .thenAnswer(inv -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new DataAccessResourceFailureException("connection refused");
                    }
                    written.addAndGet(((Collection<?>) inv.getArgument(1)).size());
                    return new int[0][];
                });
        ArchiveWriter writer = new ArchiveWriter(jdbc, recorder, dictionary, props);
        recorder.queue().offer(new ArchivePoint("a", 1, 1.0, null, true));

        writer.flush();                      // ошибка — пачка остаётся в писателе
        assertThat(writer.lastError()).contains("connection refused");
        writer.retryNow();                   // снять паузу повтора
        writer.flush();
        writer.flush();                      // лишний такт не должен записать повторно

        assertThat(written.get()).isEqualTo(1);
        assertThat(writer.lastError()).isNull();
    }

    /**
     * Ревью, Important-2: строка, которую база отвергает всегда (данные, а не связь), не должна
     * вечно повторяться вместе со всей пачкой и держать журнал действий — после трёх отказов
     * подряд при живой базе пачка пишется построчно, сбойная строка выбрасывается с учётом.
     */
    @Test
    void ядовитая_строка_выбрасывается_остальная_пачка_пишется_журнал_продолжает() throws Exception {
        ArchiveProperties props = new ArchiveProperties();
        ArchiveRecorder recorder = new ArchiveRecorder(props, new ObjectMapper());
        ArchiveTagDictionary dictionary = mock(ArchiveTagDictionary.class);
        when(dictionary.ensure(any())).thenReturn(Map.of("ok", 1, "poison", 2));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);   // база жива
        List<Object> written = new java.util.ArrayList<>();
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any()))
                .thenAnswer(inv -> {
                    Collection<?> batch = inv.getArgument(1);
                    if (batch.stream().anyMatch(p -> ((ArchivePoint) p).tag().equals("poison"))) {
                        throw new org.springframework.dao.DataIntegrityViolationException("invalid byte sequence");
                    }
                    written.addAll(batch);
                    return new int[0][];
                });
        ArchiveWriter writer = new ArchiveWriter(jdbc, recorder, dictionary, props);
        AtomicInteger journalFlushes = new AtomicInteger();
        writer.addSink(journalFlushes::incrementAndGet);
        recorder.queue().offer(new ArchivePoint("ok", 1, 1.0, null, true));
        recorder.queue().offer(new ArchivePoint("poison", 1, null, "x", true));

        for (int i = 0; i < 5; i++) {
            writer.retryNow();
            writer.flush();
        }

        assertThat(written).extracting(p -> ((ArchivePoint) p).tag()).containsExactly("ok");
        assertThat(writer.rejected()).isEqualTo(1);
        assertThat(writer.pendingSize()).isZero();
        // Пока пачка повторяется, журнал ждёт (при лежащей базе он бы терял строки); после
        // выброса ядовитой строки на третьем такте — пишется каждый такт.
        assertThat(journalFlushes.get()).isEqualTo(3);
    }
}
