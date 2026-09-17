package com.example.runtime.project;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.persistence.DriverLeaseService;
import com.example.runtime.recipe.ProcedureExecutionService;
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
    private final DriverLeaseService leases;
    private final ProcedureExecutionService procedures;

    public void activate(Long projectId) {
        if (store.get(projectId) != null) {
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

        for (String driver : project.getDrivers()) {
            if (!leases.tryAcquire(driver)) {
                log.warn("Проект {} не поднят: драйвер {} занят другим экземпляром", projectId, driver);
                return;
            }
        }

        store.put(project);
        tagValueRouter.registerProject(project);
        log.info("Проект {} поднят: {} тегов, драйверы {}",
                projectId, index.getAllTagIds().size(), project.getDrivers());
        // Незавершённые мойки продолжаются с сохранённого шага. Действия шага не
        // переприменяются: объект уже в этом состоянии.
        procedures.restore(projectId);
    }

    public void deactivate(Long projectId) {
        ProjectRuntime project = store.remove(projectId);
        if (project == null) {
            return;
        }
        procedures.persistAll(projectId);
        tagValueRouter.unregisterProject(project);
        log.info("Проект {} выведен из эксплуатации", projectId);
    }
}
