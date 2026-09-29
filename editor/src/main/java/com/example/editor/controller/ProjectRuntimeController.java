package com.example.editor.controller;

import com.example.editor.exception.NoProdReleaseException;
import com.example.editor.model.ProjectRuntimeFlag;
import com.example.editor.model.version.DocumentType;
import com.example.editor.repository.ProjectRuntimeFlagRepository;
import com.example.editor.service.RuntimeProjectsPublisher;
import com.example.editor.service.version.DocumentVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ввод проекта в эксплуатацию и выбор prod-выпуска. Пока флаг не выставлен, runtime проект не
 * поднимает: ни телеметрии, ни onChange, ни процедур. Крутит он только prod-выпуск — живое дерево
 * видит один редактор.
 * <p>
 * Без {@code @Transactional} намеренно: {@code save} репозитория коммитит сам, и запись в
 * runtime.projects уходит уже после коммита. Внутри транзакции runtime, получив запись, прочёл бы
 * из editor ещё старый флаг и prod, и смена ждала бы ресинка — до минуты.
 */
@RestController
@RequestMapping("/api/editor/projects/{projectId}/runtime")
@RequiredArgsConstructor
@Slf4j
public class ProjectRuntimeController {

    private final ProjectRuntimeFlagRepository repository;
    private final RuntimeProjectsPublisher publisher;
    private final DocumentVersionService versions;

    @GetMapping
    public Map<String, Object> get(@PathVariable Long projectId) {
        return view(repository.findById(projectId).orElseGet(() -> blank(projectId)));
    }

    @PutMapping
    public Map<String, Object> set(@PathVariable Long projectId,
                                   @RequestBody Map<String, Boolean> body,
                                   @RequestHeader(value = "X-Username", required = false) String username) {
        boolean inOperation = Boolean.TRUE.equals(body.get("inOperation"));
        ProjectRuntimeFlag flag = repository.findById(projectId).orElseGet(() -> blank(projectId));
        if (inOperation && flag.getProdVersionNo() == null) {
            throw new NoProdReleaseException(projectId);
        }
        flag.setInOperation(inOperation);
        repository.save(flag);
        publisher.publish(projectId, inOperation, flag.getProdVersionNo());
        log.info("Проект {} {} эксплуатацию (пользователь {})",
                projectId, inOperation ? "введён в" : "выведен из", username);
        return view(flag);
    }

    /**
     * Назначить выпуск prod. У проекта в эксплуатации runtime подхватит его горячо — по номеру в
     * runtime.projects; у выключенного номер просто запоминается.
     */
    @PutMapping("/prod")
    public Map<String, Object> setProd(@PathVariable Long projectId,
                                       @RequestBody Map<String, Integer> body,
                                       @RequestHeader(value = "X-Username", required = false) String username) {
        Integer versionNo = body.get("versionNo");
        if (versionNo == null) {
            throw new IllegalArgumentException("versionNo обязателен");
        }
        versions.require(DocumentType.PROJECT, projectId, versionNo); // 404, если выпуска нет
        ProjectRuntimeFlag flag = repository.findById(projectId).orElseGet(() -> blank(projectId));
        flag.setProdVersionNo(versionNo);
        repository.save(flag);
        publisher.publish(projectId, flag.isInOperation(), versionNo);
        log.info("Проект {}: prod — выпуск {} (пользователь {})", projectId, versionNo, username);
        return view(flag);
    }

    private static ProjectRuntimeFlag blank(Long projectId) {
        ProjectRuntimeFlag created = new ProjectRuntimeFlag();
        created.setProjectId(projectId);
        return created;
    }

    private static Map<String, Object> view(ProjectRuntimeFlag flag) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", flag.getProjectId());
        result.put("inOperation", flag.isInOperation());
        result.put("prodVersionNo", flag.getProdVersionNo()); // Map.of не принимает null
        return result;
    }
}
