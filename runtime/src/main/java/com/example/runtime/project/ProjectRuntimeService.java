package com.example.runtime.project;

import com.example.runtime.assignment.AssignmentState;
import com.example.runtime.automation.engine.AutomationEngine;
import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.recipe.ProcedureExecutionService;
import com.example.runtime.session.RuntimeSessionService;
import com.example.runtime.session.TagSubscriptionIndex;
import com.example.scriptcore.ProjectData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Поднятие и гашение проекта. Проект живёт по флагу «в эксплуатации» в editor и не зависит
 * от того, открыт ли монитор: гасит его только снятие флага, а не уход оператора, закрытие
 * WebSocket или уборщик брошенных сессий.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectRuntimeService {

    private final EditorClient editorClient;
    private final TagValueRouter tagValueRouter;
    private final ProjectRuntimeStore store;
    private final ProcedureExecutionService procedures;
    private final RuntimeSessionService sessions;
    private final AutomationEngine automation;
    private final AssignmentState assignments;

    public void activate(Long projectId) {
        if (store.get(projectId) != null) {
            return;
        }
        // Флаг «в эксплуатации» приходит всем экземплярам; поднимает проект только тот, кому он назначен.
        if (!assignments.isAssigned(projectId)) {
            log.debug("Проект {} не назначен этому экземпляру — не поднимаю", projectId);
            return;
        }
        // Запись в топике — только повод проверить. Решает таблица editor: иначе застрявшая в
        // компактном топике запись поднимала бы проект, который никто не включал.
        if (!editorClient.isInOperation(projectId)) {
            log.warn("Проект {} в реестре runtime.projects помечен в эксплуатацию, но в editor выключен — не поднимаю",
                    projectId);
            return;
        }
        EditorComponentDto tree = editorClient.getProjectTree(projectId);
        if (tree == null) {
            log.warn("Проект {} помечен в эксплуатацию, но editor его не отдаёт — пропускаю", projectId);
            return;
        }
        TagSubscriptionIndex index = TagSubscriptionIndex.build(tree, projectId);
        ProjectData projectData = ProjectData.parse(editorClient.getProjectData(projectId));
        ProjectRuntime project = new ProjectRuntime(projectId, tree, index, projectData);

        store.put(project);
        tagValueRouter.registerProject(project);
        log.info("Проект {} поднят: {} тегов", projectId, index.getAllTagIds().size());
        // Незавершённые мойки продолжаются с сохранённого шага. Действия шага не
        // переприменяются: объект уже в этом состоянии.
        procedures.restore(projectId);
        // Фоновые задачи — часть проекта: поднимаются вместе с ним, если есть определения.
        automation.projectActivated(projectId);
    }

    public void deactivate(Long projectId) {
        ProjectRuntime project = store.remove(projectId);
        if (project == null) {
            return;
        }
        // Сначала задачи: проект уже убран из стора, такты больше ничего не пишут, а память задач
        // сбрасывается в базу до снятия тегов.
        automation.projectDeactivated(projectId);
        procedures.persistAll(projectId);
        tagValueRouter.unregisterProject(project);
        // Мониторы отпускаются явно: иначе они остались бы подключены к этому объекту, а
        // повторное включение создаст новый, и экран молча замрёт.
        sessions.closeSessionsOf(project);
        log.info("Проект {} выведен из эксплуатации", projectId);
    }
}
