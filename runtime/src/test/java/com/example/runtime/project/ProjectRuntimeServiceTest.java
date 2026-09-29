package com.example.runtime.project;

import com.example.runtime.assignment.AssignmentState;
import com.example.runtime.automation.engine.AutomationEngine;
import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.client.dto.EditorPropertyDto;
import com.example.runtime.client.dto.EditorRuntimeFlag;
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
import static org.mockito.ArgumentMatchers.anyInt;
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
        ProjectModelLoader loader = mock(ProjectModelLoader.class);
        when(editor.getRuntime(8501L)).thenReturn(new EditorRuntimeFlag(false, 1));
        ProjectRuntimeStore store = new ProjectRuntimeStore();

        service(editor, loader, store, assigned(8501L)).activate(8501L);

        assertThat(store.get(8501L)).isNull();
        verify(loader, never()).load(any(), anyInt());
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
        when(editor.getRuntime(8501L)).thenReturn(new EditorRuntimeFlag(true, 1));
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
        ProjectModelLoader loader = mock(ProjectModelLoader.class);
        when(loader.load(8501L, 1)).thenReturn(
                new ProjectModel(1, JSON.valueToTree(root), root, TagSubscriptionIndex.build(root, 8501L)));
        PropertyValueStore saved = mock(PropertyValueStore.class);
        when(saved.load(8501L)).thenReturn(java.util.Map.of(10L, "Щелочь", 99L, "удалённое свойство"));
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        AssignmentState assignments = new AssignmentState();
        assignments.update(java.util.List.of(), java.util.Set.of(8501L));

        new ProjectRuntimeService(editor, loader, mock(TagValueRouter.class), store,
                mock(ProcedureExecutionService.class), mock(RuntimeSessionService.class), mock(AutomationEngine.class),
                assignments, saved, mock(org.springframework.context.ApplicationEventPublisher.class))
                .activate(8501L);

        ProjectRuntime project = store.get(8501L);
        assertThat(project.getPropertyValues()).containsEntry(10L, "Щелочь").doesNotContainKey(99L);
        project.putPropertyValue(10L, "Кислота");
        verify(saved).record(8501L, 10L, "Кислота");
    }
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ProjectRuntimeService service(EditorClient editor, ProjectRuntimeStore store,
                                                 AutomationEngine automation) {
        return new ProjectRuntimeService(editor, mock(ProjectModelLoader.class), mock(TagValueRouter.class), store,
                mock(ProcedureExecutionService.class), mock(RuntimeSessionService.class), automation,
                new AssignmentState(), mock(PropertyValueStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    void проект_поднимается_из_prod_выпуска_а_без_prod_не_поднимается() {
        EditorClient editor = mock(EditorClient.class);
        ProjectModelLoader loader = mock(ProjectModelLoader.class);
        when(editor.getRuntime(8501L)).thenReturn(new EditorRuntimeFlag(true, null));
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        ProjectRuntimeService service = service(editor, loader, store, assigned(8501L));

        service.activate(8501L);
        assertThat(store.get(8501L)).isNull();
        verify(loader, never()).load(any(), anyInt());

        when(editor.getRuntime(8501L)).thenReturn(new EditorRuntimeFlag(true, 3));
        when(loader.load(8501L, 3)).thenReturn(model(3, table(2L, property(10L, "mode", "0"))));
        service.activate(8501L);
        assertThat(store.get(8501L).getModel().versionNo()).isEqualTo(3);
    }

    @Test
    void замена_выпуска_выбрасывает_удалённое_свойство_и_сохраняет_остальные() {
        EditorClient editor = mock(EditorClient.class);
        ProjectModelLoader loader = mock(ProjectModelLoader.class);
        TagValueRouter router = mock(TagValueRouter.class);
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        ProjectModel v1 = model(1, table(2L, property(10L, "mode", "0"), property(11L, "old", "0")));
        ProjectRuntime project = new ProjectRuntime(8501L, v1, null);
        project.putPropertyValue(10L, "Щелочь");
        store.put(project);
        when(editor.getRuntime(8501L)).thenReturn(new EditorRuntimeFlag(true, 2));
        when(loader.load(8501L, 2)).thenReturn(
                model(2, table(2L, property(10L, "mode", "0"), property(12L, "fresh", "5"))));

        service(editor, loader, store, assigned(8501L), router).reload(8501L, 2);

        assertThat(store.get(8501L)).isSameAs(project);
        assertThat(project.getModel().versionNo()).isEqualTo(2);
        assertThat(project.getPropertyValues())
                .containsEntry(10L, "Щелочь").containsEntry(12L, "5").doesNotContainKey(11L);
        verify(router).replaceProject(project, v1.index());
    }

    @Test
    void ошибка_загрузки_выпуска_оставляет_прежнюю_модель() {
        EditorClient editor = mock(EditorClient.class);
        ProjectModelLoader loader = mock(ProjectModelLoader.class);
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        ProjectRuntime project = new ProjectRuntime(8501L, model(1, table(2L)), null);
        store.put(project);
        when(editor.getRuntime(8501L)).thenReturn(new EditorRuntimeFlag(true, 2));
        when(loader.load(8501L, 2)).thenThrow(new IllegalStateException("editor недоступен"));

        service(editor, loader, store, assigned(8501L)).reload(8501L, 2);

        assertThat(project.getModel().versionNo()).isEqualTo(1);
    }

    @Test
    void тот_же_номер_из_топика_не_ходит_в_editor() {
        EditorClient editor = mock(EditorClient.class);
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        store.put(new ProjectRuntime(8501L, model(3, table(2L)), null));

        service(editor, mock(ProjectModelLoader.class), store, assigned(8501L)).reload(8501L, 3);

        verify(editor, never()).getRuntime(any());
    }

    static ProjectModel model(int versionNo, EditorComponentDto... children) {
        EditorComponentDto root = new EditorComponentDto();
        root.setId(1L);
        root.setType("project");
        root.setChildren(java.util.List.of(children));
        return new ProjectModel(versionNo, JSON.valueToTree(root), root, TagSubscriptionIndex.build(root, 8501L));
    }

    static EditorComponentDto table(long id, EditorPropertyDto... properties) {
        EditorComponentDto c = new EditorComponentDto();
        c.setId(id);
        c.setType("table");
        c.setName("Компонент " + id);
        c.setProperties(java.util.List.of(properties));
        return c;
    }

    static EditorPropertyDto property(long id, String name, String defaultValue) {
        EditorPropertyDto p = new EditorPropertyDto();
        p.setId(id);
        p.setName(name);
        p.setDefault_value(defaultValue);
        return p;
    }

    static AssignmentState assigned(long projectId) {
        AssignmentState a = new AssignmentState();
        a.update(java.util.List.of(), java.util.Set.of(projectId));
        return a;
    }

    static ProjectRuntimeService service(EditorClient editor, ProjectModelLoader loader, ProjectRuntimeStore store,
                                         AssignmentState assignments) {
        return service(editor, loader, store, assignments, mock(TagValueRouter.class));
    }

    static ProjectRuntimeService service(EditorClient editor, ProjectModelLoader loader, ProjectRuntimeStore store,
                                         AssignmentState assignments, TagValueRouter router) {
        return new ProjectRuntimeService(editor, loader, router, store, mock(ProcedureExecutionService.class),
                mock(RuntimeSessionService.class), mock(AutomationEngine.class), assignments,
                mock(PropertyValueStore.class), mock(org.springframework.context.ApplicationEventPublisher.class));
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
