package com.example.runtime.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
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
}
