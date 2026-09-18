package com.example.runtime.automation.engine;

import com.example.runtime.automation.AutomationEngineProperties;
import com.example.runtime.automation.AutomationStateBridge;
import com.example.runtime.automation.store.AutomationStore;
import com.example.runtime.instance.InstanceIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * scada-e17: задача, удалённая из набора, оставалась в task_status навсегда. Чистить нужно и базу,
 * и буфер: накопленный статус иначе ушёл бы следующим сбросом уже после удаления.
 */
class StateSinkRetainTest {

    private static TaskStatusUpdate status(long taskId) {
        return new TaskStatusUpdate(8501L, taskId, "t" + taskId, TaskState.RUNNING, 1L, 0L, null, 0, 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retain_dropsBufferedStatusOfRemovedTask_andDeletesItsRows() {
        AutomationStore store = mock(AutomationStore.class);
        StateSink sink = new StateSink(store, mock(AutomationStateBridge.class), new AutomationEngineProperties(),
                mock(InstanceIdentity.class));
        sink.status(status(6));
        sink.status(status(7));

        sink.retain(8501L, Set.of(7L), Set.of());
        sink.flush();

        verify(store).deleteObsolete(8501L, Set.of(7L), Set.of());
        ArgumentCaptor<List<AutomationStore.StatusRow>> saved = ArgumentCaptor.forClass(List.class);
        verify(store).saveStatuses(saved.capture());
        assertThat(saved.getValue()).extracting(AutomationStore.StatusRow::taskId).containsExactly(7L);
    }
}
