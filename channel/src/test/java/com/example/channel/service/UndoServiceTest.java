package com.example.channel.service;

import com.example.channel.config.command.CommandManager;
import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandLogRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * scada-thq: {@code findAllById} молча отбрасывает id, которых нет в журнале, и такой id не
 * попадал ни в отмену, ни в список неудавшихся — вызывающий видел пустой ответ и считал, что
 * всё отменено.
 */
class UndoServiceTest {

    @Test
    void missingLogId_isReportedAsFailed() {
        CommandLogRepository repository = mock(CommandLogRepository.class);
        CommandLog existing = new CommandLog();
        existing.setId(1L);
        when(repository.findAllById(List.of(1L, 42L))).thenReturn(List.of(existing));
        UndoService service = new UndoService(repository, mock(CommandManager.class), List.of(),
                mock(UndoExecutor.class));

        assertThat(service.undoLogs(List.of(1L, 42L), "tester")).containsExactly(42L);
    }
}
