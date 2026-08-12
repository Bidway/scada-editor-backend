package com.example.editor.service.version;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.component.ComponentRepository;
import com.example.editor.service.ComponentService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SceneDocumentSource implements DocumentSource {

    private final ComponentRepository componentRepository;
    private final ComponentMapper componentMapper;
    private final ObjectMapper objectMapper;
    private final ComponentService componentService;

    /**
     * {@code ComponentService} внедряется лениво: {@code ComponentServiceImpl} сам зависит от
     * этого бина (снимок при сохранении), и без прокси контекст не поднимется из-за цикла.
     */
    public SceneDocumentSource(ComponentRepository componentRepository,
                               ComponentMapper componentMapper,
                               ObjectMapper objectMapper,
                               @Lazy ComponentService componentService) {
        this.componentRepository = componentRepository;
        this.componentMapper = componentMapper;
        this.objectMapper = objectMapper;
        this.componentService = componentService;
    }

    @Override
    public DocumentType type() {
        return DocumentType.SCENE;
    }

    /**
     * {@code ComponentMapper.toDto} строит дерево целиком сам — рекурсивно, через
     * {@code @AfterMapping mapChildren}. Отдельного {@code toDtoTree} в модуле нет, что бы ни
     * говорила спека.
     * <p>
     * Своя транзакция, а не расчёт на open-in-view: коллекции компонента ленивые, и обход
     * дерева вне веб-запроса (фоновая задача, прямой вызов из теста) иначе падает
     * {@code LazyInitializationException}. Полагаться на то, что вызывающий окажется внутри
     * запроса, — предположение, которое сломается молча.
     */
    @Override
    @Transactional(readOnly = true)
    public JsonNode contentOf(Long sceneId) {
        Component scene = componentRepository.findById(sceneId)
                .orElseThrow(() -> new NotFoundException("Scene not found: " + sceneId));
        if (!ComponentTypes.SCENE.equals(scene.getType())) {
            throw new IllegalStateException("Component " + sceneId + " is not a scene");
        }
        return objectMapper.valueToTree(componentMapper.toDto(scene));
    }

    /**
     * Восстановление — синхронизация поддерева сцены со снимком, а не обычный {@code PUT}:
     * {@code ComponentService.update} правит перечисленное и ничего не удаляет, а на компонент
     * с исчезнувшим id падает. Поэтому три хода: убрать появившееся после снимка, снять id у
     * пропавшего, остальное обновить.
     * <p>
     * Лишнее убирается **через граф сущностей**, а не вызовом удаления по id: коллекция
     * {@code children} объявлена с {@code cascade = ALL}, и пока удалённый компонент остаётся в
     * коллекции живого родителя, каскад воскрешает его на flush — проверено, тест видел
     * компонент на месте после удаления. Выбытие из коллекции с {@code orphanRemoval} уносит и
     * всё его поддерево, поэтому обход рекурсивный: компонент, добавленный после снимка на
     * третьем уровне, иначе остался бы.
     * <p>
     * Корень сцены в {@code update} не отдаём: {@code populateComponent} запрещает сохранять
     * компонент с типом scene или project.
     */
    @Override
    @Transactional
    public void restore(Long sceneId, JsonNode content, String userName) {
        List<ComponentCreateDto> children = objectMapper.convertValue(
                content.get("children"), new TypeReference<List<ComponentCreateDto>>() {});

        Set<Long> keep = new HashSet<>();
        collectIds(children, keep);

        Component scene = componentRepository.findById(sceneId)
                .orElseThrow(() -> new NotFoundException("Scene not found: " + sceneId));
        pruneObsolete(scene, keep);
        componentRepository.saveAndFlush(scene);

        // Компонента, удалённого после снимка, в базе больше нет — id снимаем, он создастся
        // заново. Свой прежний id он не вернёт, но альтернатива — падение всего восстановления.
        dropMissingIds(children);

        for (ComponentCreateDto child : children) {
            child.setParent_id(sceneId);
        }
        componentService.update(children, userName, VersionKind.RESTORE);
    }

    /** Всё, чего нет в снимке, выбывает из коллекции родителя — orphanRemoval доделает. */
    private void pruneObsolete(Component parent, Set<Long> keep) {
        parent.getChildren().removeIf(child -> !keep.contains(child.getId()));
        for (Component child : parent.getChildren()) {
            pruneObsolete(child, keep);
        }
    }

    private void collectIds(List<ComponentCreateDto> dtos, Set<Long> into) {
        for (ComponentCreateDto dto : dtos) {
            if (dto.getId() != null) {
                into.add(dto.getId());
            }
            if (dto.getChildren() != null) {
                collectIds(dto.getChildren(), into);
            }
        }
    }

    private void dropMissingIds(List<ComponentCreateDto> dtos) {
        for (ComponentCreateDto dto : dtos) {
            if (dto.getId() != null && !componentRepository.existsById(dto.getId())) {
                dto.setId(null);
            }
            if (dto.getChildren() != null) {
                dropMissingIds(dto.getChildren());
            }
        }
    }
}
