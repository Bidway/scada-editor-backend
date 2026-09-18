package com.example.runtime.project;

import com.example.runtime.assignment.AssignmentState;
import com.example.runtime.automation.engine.AutomationEngine;
import com.example.runtime.client.EditorClient;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.recipe.ProcedureExecutionService;
import com.example.runtime.session.RuntimeSessionService;
import com.example.runtime.session.TagSubscriptionIndex;
import com.example.scriptcore.ProjectData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        com.example.runtime.assignment.AssignmentState assignments = new com.example.runtime.assignment.AssignmentState();
        assignments.update(java.util.List.of(), java.util.Set.of(8501L));
        ProjectRuntimeService service = new ProjectRuntimeService(editor, mock(TagValueRouter.class), store,
                procedures,
                mock(com.example.runtime.session.RuntimeSessionService.class),
                mock(com.example.runtime.automation.engine.AutomationEngine.class), assignments,
                mock(PropertyValueStore.class));

        service.activate(8501L);

        assertThat(store.get(8501L)).isNull();
        verify(editor, never()).getProjectTree(any());
        verify(procedures, never()).restore(any());
    }

    /**
     * Скрипты кнопок и шаги процедур читают данные из {@link ProjectRuntime}, а не из фоновых
     * задач. Пока reload перечитывал только задачи, правка таблицы вариантов доезжала до кнопки
     * лишь после снятия и возврата флага «в эксплуатации» (scada-zd1w).
     */
    @Test
    void reload_подменяет_данные_проекта_для_скриптов_и_перечитывает_задачи() throws Exception {
        EditorClient editor = mock(EditorClient.class);
        AutomationEngine automation = mock(AutomationEngine.class);
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        store.put(new ProjectRuntime(8501L, mock(TagSubscriptionIndex.class), dataWithAlkali("1.5")));
        when(editor.getProjectData(8501L)).thenReturn(JSON.readTree(tablesWithAlkali("2.0")));

        assertThat(service(editor, store, automation).reloadData(8501L)).isTrue();

        assertThat(alkali(store.get(8501L).getProjectData())).isEqualTo(2.0);
        verify(automation).reloadData(8501L);
    }

    @Test
    void reload_при_недоступном_editor_оставляет_прежние_данные() throws Exception {
        EditorClient editor = mock(EditorClient.class);
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        store.put(new ProjectRuntime(8501L, mock(TagSubscriptionIndex.class), dataWithAlkali("1.5")));
        when(editor.getProjectData(8501L)).thenThrow(new IllegalStateException("editor недоступен"));

        assertThatThrownBy(() -> service(editor, store, mock(AutomationEngine.class)).reloadData(8501L))
                .isInstanceOf(IllegalStateException.class);

        assertThat(alkali(store.get(8501L).getProjectData())).isEqualTo(1.5);
    }

    /**
     * scada-vrkf: значения, записанные скриптами до перезапуска, возвращаются при подъёме проекта
     * поверх default_value; свойство, которого в дереве больше нет, отбрасывается; новая запись
     * скриптом уходит в хранилище.
     */
    @Test
    void подъём_проекта_возвращает_сохранённые_значения_свойств() {
        EditorClient editor = mock(EditorClient.class);
        when(editor.isInOperation(8501L)).thenReturn(true);
        com.example.runtime.client.dto.EditorComponentDto root = new com.example.runtime.client.dto.EditorComponentDto();
        root.setId(1L);
        root.setType("project");
        com.example.runtime.client.dto.EditorComponentDto table = new com.example.runtime.client.dto.EditorComponentDto();
        table.setId(2L);
        table.setType("table");
        table.setName("Режим");
        com.example.runtime.client.dto.EditorPropertyDto mode = new com.example.runtime.client.dto.EditorPropertyDto();
        mode.setId(10L);
        mode.setName("mode");
        mode.setDefault_value("0");
        table.setProperties(java.util.List.of(mode));
        root.setChildren(java.util.List.of(table));
        when(editor.getProjectTree(8501L)).thenReturn(root);
        PropertyValueStore saved = mock(PropertyValueStore.class);
        when(saved.load(8501L)).thenReturn(java.util.Map.of(10L, "Щелочь", 99L, "удалённое свойство"));
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        AssignmentState assignments = new AssignmentState();
        assignments.update(java.util.List.of(), java.util.Set.of(8501L));

        new ProjectRuntimeService(editor, mock(TagValueRouter.class), store, mock(ProcedureExecutionService.class),
                mock(RuntimeSessionService.class), mock(AutomationEngine.class), assignments, saved)
                .activate(8501L);

        ProjectRuntime project = store.get(8501L);
        assertThat(project.getPropertyValues()).containsEntry(10L, "Щелочь").doesNotContainKey(99L);
        project.putPropertyValue(10L, "Кислота");
        verify(saved).record(8501L, 10L, "Кислота");
    }
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ProjectRuntimeService service(EditorClient editor, ProjectRuntimeStore store,
                                                 AutomationEngine automation) {
        return new ProjectRuntimeService(editor, mock(TagValueRouter.class), store,
                mock(ProcedureExecutionService.class), mock(RuntimeSessionService.class), automation,
                new AssignmentState(), mock(PropertyValueStore.class));
    }

    private static String tablesWithAlkali(String value) {
        return "{\"tables\":[{\"name\":\"station_presets\",\"columns\":[{\"name\":\"CONCENTRATION_ALKALI\","
                + "\"value_type\":\"float\"}],\"rows\":[{\"key\":\"disinfection\","
                + "\"values\":{\"CONCENTRATION_ALKALI\":" + value + "}}]}]}";
    }

    private static ProjectData dataWithAlkali(String value) throws Exception {
        return ProjectData.parse(JSON.readTree(tablesWithAlkali(value)));
    }

    private static Object alkali(ProjectData data) {
        return data.table("station_presets").rowsByKey().get("disinfection").get("CONCENTRATION_ALKALI");
    }
}
