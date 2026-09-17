package com.example.runtime.project;

import com.example.runtime.client.EditorClient;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.persistence.DriverLeaseService;
import com.example.runtime.recipe.ProcedureExecutionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Топик runtime.projects — сигнал, а не источник истины. В нём однажды осталась запись
 * «8501 в эксплуатации», которую записал тест editor, и runtime поднял проект, который в
 * editor никто не включал (scada-ocqj).
 */
class ProjectRuntimeServiceTest {

    @Test
    void проект_выключенный_в_editor_не_поднимается_по_записи_топика() {
        EditorClient editor = mock(EditorClient.class);
        when(editor.isInOperation(8501L)).thenReturn(false);
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        ProcedureExecutionService procedures = mock(ProcedureExecutionService.class);
        ProjectRuntimeService service = new ProjectRuntimeService(editor, mock(TagValueRouter.class), store,
                mock(DriverLeaseService.class), procedures,
                mock(com.example.runtime.session.RuntimeSessionService.class));

        service.activate(8501L);

        assertThat(store.get(8501L)).isNull();
        verify(editor, never()).getProjectTree(any());
        verify(procedures, never()).restore(any());
    }
}
