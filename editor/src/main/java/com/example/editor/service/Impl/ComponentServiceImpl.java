package com.example.editor.service.Impl;

import com.example.editor.command.component.*;
import com.example.editor.config.command.CommandManager;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.component.ComponentSaveResponseDto;
import com.example.editor.dto.component.ComponentStateDto;
import com.example.editor.dto.project.ProjectCreateDto;
import com.example.editor.dto.project.ProjectCreateResponseDto;
import com.example.editor.dto.project.ProjectsResponseDto;
import com.example.editor.dto.scene.SceneCreateDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.dto.scene.ScenesResponseDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.exception.VersionMismatchException;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.merge.ComponentTreePruner;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentState;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.component.ComponentRepository;
import com.example.editor.service.ComponentService;
import com.example.editor.service.version.DocumentVersionService;
import com.example.editor.service.version.SceneDocumentSource;
import com.example.editor.service.version.SceneMergeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ComponentServiceImpl implements ComponentService {

    private final ComponentRepository repository;
    private final ComponentPropertyRepository propertyRepository;
    private final ObjectMapper mapper;
    private final CommandManager commandManager;
    private final ComponentMapper componentMapper;
    private final DocumentVersionService versionService;
    private final SceneDocumentSource sceneDocumentSource;
    private final SceneMergeService sceneMergeService;

    /**
     * Проверка версии, запись данных и запись снимка — одна транзакция.
     * <p>
     * Пока их было три, сбой снимка оставлял данные записанными без следа в истории, а два
     * одновременных сохранения оба проходили проверку, оба коммитили данные и второй бился о
     * {@code document_version_uk}: клиент получал 500, хотя его правка уже лежала в базе
     * (scada-78j). Теперь неудача снимка забирает данные с собой, а гонка отвечает 409 — см.
     * {@link DocumentVersionService#record}.
     */
    @Override
    @Transactional
    public ComponentSaveResponseDto create(List<ComponentCreateDto> dtos, String userName,
                                           VersionKind kind, Integer basedOnVersion) {
        requireBaseUnlessRestoring(dtos, kind, basedOnVersion);
        List<Component> prepared = dtos.stream().map(dto -> buildComponent(dto, null)).toList();
        List<ComponentResponseDto> response = commandManager.execute(
                new CreateComponentCommand(repository, prepared, componentMapper, mapper, userName));
        Integer versionNo = snapshotScenesOf(prepared, userName, kind, basedOnVersion);
        return new ComponentSaveResponseDto(mapper.valueToTree(response), versionNo, null);
    }

    @Override
    public ProjectCreateResponseDto createProject(ProjectCreateDto dto, String userName) {
        return commandManager.execute(new CreateProjectCommand(repository, dto, mapper, componentMapper, userName));
    }

    @Override
    public List<ProjectsResponseDto> getProjects() {
        return componentMapper.toProjectsDtoList(
                repository.findByParentIsNullAndType(ComponentTypes.PROJECT));
    }

    @Override
    public SceneCreateResponseDto createScene(SceneCreateDto dto, String userName) {
        return commandManager.execute(new CreateSceneCommand(repository, dto, mapper, componentMapper, userName));
    }

    @Override
    public List<ScenesResponseDto> getScenes(Long projectId) {
        if (projectId != null) {
            return componentMapper.toScenesDtoList(
                    repository.findByParentIdAndType(projectId, ComponentTypes.SCENE));
        }
        return componentMapper.toScenesDtoList(repository.findByType(ComponentTypes.SCENE));
    }

    /**
     * Одна транзакция на данные и снимок — по той же причине, что в {@link #create}.
     * <p>
     * {@code sceneId} обязателен для клиентских вызовов: с планом 3b {@code PUT} несёт сцену
     * целиком, и без него по присланному списку (тем более пустому) не понять, чьи это дети —
     * а «стереть не ту сцену» здесь самая дорогая ошибка. Восстановление версии
     * ({@link SceneDocumentSource#restore}) сцену не передаёт: оно само вычищает лишнее до
     * вызова {@code update} и зовёт четырёхаргументную обёртку с {@code kind = RESTORE}.
     * <p>
     * Порядок внутри метода обязателен: сначала чистка {@link #deleteMissing}, потом запись
     * присланных dto. В обратном порядке (как было исправлено C1) новый компонент без id сперва
     * вставляется и получает id ({@code IDENTITY}), а следом чистка не находит его в {@code keep}
     * (там лежат только явные id из dto) и удаляет только что созданную строку — клиент получает
     * 200 с id несуществующей записи. {@link SceneDocumentSource#restore} делает то же самое в
     * том же порядке — чистка прежде записи.
     * <p>
     * Расхождение версий (план 3b) сначала пробует слиться, а не отказывает сразу, поэтому вместо
     * {@link DocumentVersionService#requireBase} (он безусловно отказывает) здесь зовётся его
     * сосед {@link DocumentVersionService#requireBaseVersion}: та же обязательность {@code
     * based_on_version}, но с номером текущей версии на руках — он нужен и для решения «сливать
     * или нет», и для самого вызова слияния. Автосохранение в развилку не попадает — {@code
     * AUTOSAVE} при расхождении отказывается безусловно, как и раньше, потому что блок {@code
     * merged} в ответе некому прочитать, когда сохранение сделал таймер, а не человек. Чистка
     * {@link #deleteMissing} после развилки идёт по слитому дереву, а не по присланному {@code
     * dtos}: иначе компоненты, добавленные чужой стороной, выглядели бы «отсутствующими в
     * запросе» и чистка вычистила бы их же.
     */
    @Override
    @Transactional
    public ComponentSaveResponseDto update(List<ComponentCreateDto> dtos, String userName,
                                           VersionKind kind, Integer basedOnVersion, Long sceneId) {
        if (sceneId == null && kind != VersionKind.RESTORE) {
            throw new IllegalArgumentException(
                    "scene_id is required: PUT carries the whole scene, so a missing component"
                            + " means it was deleted");
        }
        List<ComponentCreateDto> tree = dtos;
        SceneMergeService.MergeOutcome outcome = null;
        if (sceneId != null) {
            Component scene = requireScene(sceneId);
            if (kind != VersionKind.RESTORE) {
                Integer current =
                        versionService.requireBaseVersion(DocumentType.SCENE, sceneId, basedOnVersion);
                if (current != null && !current.equals(basedOnVersion)) {
                    if (kind == VersionKind.AUTOSAVE) {
                        // Автосохранение не сливает: блок merged некому прочитать.
                        throw new VersionMismatchException(basedOnVersion, current);
                    }
                    outcome = sceneMergeService.merge(sceneId, dtos, basedOnVersion, current);
                    tree = outcome.tree();
                }
            }
            deleteMissing(scene, tree);
        }
        List<Component> prepared = tree.stream().map(this::updateComponent).toList();
        List<ComponentResponseDto> response = commandManager.execute(
                new UpdateComponentCommand(repository, prepared, componentMapper, mapper, userName));
        Integer versionNo = snapshotScenesOf(prepared, userName, kind, basedOnVersion);
        return new ComponentSaveResponseDto(mapper.valueToTree(response), versionNo, null,
                mergedReport(outcome));
    }

    /** Блок {@code merged} контракта; {@code null}, если слияния не было. */
    private Map<String, Object> mergedReport(SceneMergeService.MergeOutcome outcome) {
        if (outcome == null || outcome.changes().isEmpty()) {
            return null;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("base_version", outcome.baseVersion());
        merged.put("merged_with_version", outcome.mergedWithVersion());
        merged.put("changes", outcome.changes().stream().map(change -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_name", outcome.theirUser());
            row.put("entity", change.entity());
            row.put("path", change.path());
            row.put("change", change.change().name());
            return row;
        }).toList());
        return merged;
    }

    /**
     * {@code scene_id} обязан адресовать именно сцену: чистка ниже удаляет всё её поддерево,
     * которого нет в присланном списке, и по чужому id (проект, обычный компонент) вычистила бы
     * не то. {@code populateComponent} проверяет тип так же явно при создании — здесь тот же
     * принцип, только на входе в {@code update}, до любой записи.
     */
    private Component requireScene(Long sceneId) {
        Component scene = repository.findById(sceneId)
                .orElseThrow(() -> new NotFoundException("Scene not found: " + sceneId));
        if (!ComponentTypes.SCENE.equals(scene.getType())) {
            throw new IllegalArgumentException(
                    "scene_id " + sceneId + " does not address a scene (type=" + scene.getType() + ")");
        }
        return scene;
    }

    /**
     * Компоненты сцены, которых нет в присланном дереве, удаляются: тело {@code PUT} — это
     * состав сцены целиком, а не список правок.
     * <p>
     * Зовётся ДО записи присланных dto — см. предупреждение о порядке в {@link #update}.
     * Удаление идёт <b>через граф</b>, выбытием из коллекции родителя: связь объявлена с
     * {@code cascade = ALL} и {@code orphanRemoval}, и пока компонент остаётся в коллекции
     * живого родителя, каскад воскрешает его на flush. Тот же приём и тот же обход дерева, что в
     * {@link SceneDocumentSource#restore} — вынесен в {@link ComponentTreePruner}, чтобы не
     * заводить вторую копию.
     */
    private void deleteMissing(Component scene, List<ComponentCreateDto> sent) {
        Set<Long> keep = new HashSet<>();
        ComponentTreePruner.collectIds(sent, keep);
        ComponentTreePruner.pruneObsolete(scene, keep);
        repository.saveAndFlush(scene);
    }

    /**
     * Удаление компонента — такое же изменение сцены, как правка, и в истории обязано быть
     * видно, а версию обязано проверять точно так же, как {@link #update} (scada-ybr): без
     * этого DELETE был единственной дверью, через которую «последний победил» всё ещё
     * возвращался после того, как её закрыли в PUT. Сцены вычисляем ДО удаления: после него
     * подниматься будет не от кого. Проверка версии — тоже до {@link CommandManager#execute}:
     * иначе отказ придёт уже после того, как данные записаны.
     */
    @Override
    @Transactional
    public void delete(List<Long> ids, String userName, VersionKind kind, Integer basedOnVersion) {
        Set<Long> sceneIds = new LinkedHashSet<>();
        for (Long id : ids) {
            repository.findById(id)
                    .map(this::sceneRootIdOf)
                    .filter(Objects::nonNull)
                    .ifPresent(sceneIds::add);
        }
        for (Long sceneId : sceneIds) {
            versionService.requireBase(DocumentType.SCENE, sceneId, basedOnVersion);
        }
        commandManager.execute(new DeleteComponentCommand(repository, ids, userName, mapper));
        snapshotScenes(sceneIds, userName, kind, basedOnVersion);
    }

    /**
     * Сохранение принимает плоский список компонентов и про сцену ничего не знает — «сцена
     * целиком» это договорённость фронта, а не форма API. Поэтому сцену для снимка ищем сами:
     * поднимаемся по родителям до компонента с типом scene. Обычно она одна, но запрос вправе
     * задеть несколько, и тогда снимков будет несколько.
     */
    private Integer snapshotScenesOf(List<Component> saved, String userName, VersionKind kind,
                                     Integer basedOnVersion) {
        Set<Long> sceneIds = new LinkedHashSet<>();
        for (Component component : saved) {
            Long sceneId = sceneRootIdOf(component);
            if (sceneId != null) {
                sceneIds.add(sceneId);
            }
        }
        return snapshotScenes(sceneIds, userName, kind, basedOnVersion);
    }

    private Integer snapshotScenes(Set<Long> sceneIds, String userName, VersionKind kind,
                                   Integer basedOnVersion) {
        Integer last = null;
        for (Long sceneId : sceneIds) {
            last = versionService.record(DocumentType.SCENE, sceneId,
                    sceneDocumentSource.contentOf(sceneId), userName, kind, null, basedOnVersion)
                    .getVersionNo();
        }
        return last;
    }

    /**
     * Восстановление — тоже {@code update} изнутри ({@link SceneDocumentSource#restore}), но
     * без клиента и без версии на входе: оно всегда дописывает новую версию поверх текущей,
     * какой бы она ни была, поэтому проверка тут смысла не имеет и обязана пропускаться.
     * Клиент этот путь подделать не может: {@code save_kind=RESTORE} отклоняется раньше,
     * в {@link com.example.editor.model.version.VersionKinds#orManual}.
     */
    private void requireBaseUnlessRestoring(List<ComponentCreateDto> dtos, VersionKind kind,
                                            Integer basedOnVersion) {
        if (kind != VersionKind.RESTORE) {
            requireBaseForScenesOf(dtos, basedOnVersion);
        }
    }

    /**
     * Сцену для проверки ищем по присланным dto, а не по подготовленным сущностям: проверка
     * обязана сработать до того, как что-либо записано. Для новых компонентов сцена берётся из
     * {@code parent_id}, для существующих — подъёмом по дереву от самого компонента.
     */
    private void requireBaseForScenesOf(List<ComponentCreateDto> dtos, Integer basedOnVersion) {
        Set<Long> sceneIds = new LinkedHashSet<>();
        for (ComponentCreateDto dto : dtos) {
            Long anchor = dto.getId() != null ? dto.getId() : dto.getParent_id();
            if (anchor == null) {
                continue;
            }
            repository.findById(anchor)
                    .map(this::sceneRootIdOf)
                    .filter(Objects::nonNull)
                    .ifPresent(sceneIds::add);
        }
        for (Long sceneId : sceneIds) {
            versionService.requireBase(DocumentType.SCENE, sceneId, basedOnVersion);
        }
    }

    /** Связь parent ленивая — подниматься можно только пока открыта сессия. */
    private Long sceneRootIdOf(Component component) {
        Component current = component;
        while (current != null) {
            if (ComponentTypes.SCENE.equals(current.getType())) {
                return current.getId();
            }
            current = current.getParent();
        }
        return null;
    }

    @Override
    public ComponentResponseDto getById(Long id) {
        return componentMapper.toDto(repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Component not found: " + id)));
    }

    @Override
    public List<ComponentResponseDto> getAll() {
        return componentMapper.toDtoList(repository.findAll());
    }

    private Component buildComponent(ComponentCreateDto dto, Component parent) {
        Component entity;
        if (dto.getId() != null) {
            entity = repository.findById(dto.getId())
                    .orElseThrow(() -> new IllegalStateException("Component not found: " + dto.getId()));
        } else {
            entity = new Component();
        }

        Component resolvedParent = null;
        if (dto.getParent_id() != null) {
            resolvedParent = repository.findById(dto.getParent_id())
                    .orElseThrow(() -> new IllegalStateException("Parent not found: " + dto.getParent_id()));
        } else if (parent != null) {
            resolvedParent = parent;
        }

        ComponentHierarchyValidator.validateParentForCreate(resolvedParent, dto.getType());
        return populateComponent(entity, dto, resolvedParent);
    }

    /**
     * Компонент без id здесь — новый: сохранение принимает дерево целиком, и в одном списке
     * законно едут и существующие узлы, и добавленные. Внутри дерева это и так работало
     * ({@code populateComponent} заводит новую сущность дочернему dto без id), а на верхнем
     * уровне {@code findById(null)} валился в 500 из недр Spring Data. Ловилось это
     * восстановлением версии, в снимке которой есть компонент, удалённый после снимка:
     * {@code SceneDocumentSource} снимает у него id — и попадает ровно сюда (scada-yxk).
     */
    private Component updateComponent(ComponentCreateDto dto) {
        Component entity = dto.getId() == null
                ? new Component()
                : repository.findById(dto.getId())
                .orElseThrow(() -> new IllegalStateException("Component not found: " + dto.getId()));

        Component resolvedParent = null;
        if (dto.getParent_id() != null) {
            resolvedParent = repository.findById(dto.getParent_id())
                    .orElseThrow(() -> new IllegalStateException("Parent not found: " + dto.getParent_id()));
        }

        ComponentHierarchyValidator.validateParentForCreate(resolvedParent, dto.getType());
        return populateComponent(entity, dto, resolvedParent);
    }

    private Component populateComponent(Component entity, ComponentCreateDto dto, Component parent) {
        entity.setName(dto.getName());
        entity.setType(dto.getType());

        if (ComponentTypes.PROJECT.equals(dto.getType()) || ComponentTypes.SCENE.equals(dto.getType())) {
            throw new IllegalStateException("Use dedicated endpoints to create projects and scenes");
        }

        entity.setVersion(dto.getVersion());
        entity.setParent(parent);

        applyStates(entity, dto);

        entity.getChildren().clear();
        if (dto.getChildren() != null) {
            List<Component> children = dto.getChildren().stream()
                    .map(childDto -> {
                        Component childEntity;
                        if (childDto.getId() != null) {
                            childEntity = repository.findById(childDto.getId())
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Child component not found: " + childDto.getId()));
                        } else {
                            childEntity = new Component();
                        }
                        return populateComponent(childEntity, childDto, entity);
                    })
                    .collect(Collectors.toList());
            entity.getChildren().addAll(children);
        }

        ComponentScriptBindingApplier.applyProperties(entity, dto, repository::flush);
        ComponentScriptBindingApplier.apply(entity, dto, propertyRepository, repository::flush);
        return entity;
    }

    /**
     * Синхронизация состояний компонента. Состояние с тем же именем переиспользуется, а не
     * пересоздаётся: имя — его адрес ({@code setState('Открыт')} в биндингах и обработчиках),
     * а прежний {@code clear()} с повторной вставкой менял id всех состояний при каждом
     * сохранении сцены. Ссылок по id на состояния в контуре нет, поэтому падений это не давало —
     * но графика самого частого объекта переписывалась целиком на каждое сохранение, а
     * последовательность id росла без причины. Тот же приём, что для свойств, скриптов и
     * обработчиков: см. {@code ComponentScriptBindingApplier}.
     * <p>
     * Имена состояний обязаны различаться: {@code setState} иначе не смог бы выбрать нужное.
     */
    private void applyStates(Component entity, ComponentCreateDto dto) {
        if (dto.getStates() == null) {
            entity.getStates().clear();
            return;
        }
        Map<String, ComponentState> existingByName = new HashMap<>();
        Map<Long, ComponentState> existingById = new HashMap<>();
        Map<Long, String> originalNames = new HashMap<>();
        for (ComponentState existing : entity.getStates()) {
            existingByName.put(ComponentScriptBindingApplier.matchKey(existing.getName()), existing);
            if (existing.getId() != null) {
                existingById.put(existing.getId(), existing);
                originalNames.put(existing.getId(),
                        ComponentScriptBindingApplier.matchKey(existing.getName()));
            }
        }

        Set<Long> keptIds = new HashSet<>();
        // Первый проход — только явные id: они старше сопоставления по имени. Иначе элемент
        // без id, пришедший раньше по списку, успел бы забрать строку, которую следующий элемент
        // адресует по id, и две разные сущности молча слились бы в одну (одна из них теряется).
        Map<ComponentStateDto, ComponentState> resolved = new IdentityHashMap<>();
        for (ComponentStateDto s : dto.getStates()) {
            if (s.getId() == null) {
                continue;
            }
            ComponentState target = existingById.get(s.getId());
            if (target == null) {
                throw new IllegalStateException(
                        "State " + s.getId() + " does not belong to component " + entity.getId());
            }
            if (!keptIds.add(target.getId())) {
                throw new IllegalStateException(
                        "State " + s.getId() + " is addressed twice in the same request");
            }
            // Тот же захват ключа, что в applyScripts.
            existingByName.remove(ComponentScriptBindingApplier.matchKey(target.getName()));
            resolved.put(s, target);
        }

        List<ComponentState> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (ComponentStateDto s : dto.getStates()) {
            if (s.getName() == null || s.getName().isBlank()) {
                throw new IllegalStateException("State name is required");
            }
            String name = s.getName().trim();
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate state name '" + name + "' in component " + entity.getId()
                                + "; setState() addresses states by name, so names must be unique");
            }
            ComponentState target = s.getId() != null ? resolved.get(s) : existingByName.remove(name);
            if (target == null) {
                target = new ComponentState();
                target.setComponent(entity);
            }
            target.setName(name);
            target.setImage(stripEvents(s.getImage()));
            target.setIsDefault(s.getIsDefault());
            if (target.getId() != null) {
                keptIds.add(target.getId());
            }
            incoming.add(target);
        }

        ComponentScriptBindingApplier.freeContestedKeys(incoming, originalNames,
                ComponentState::getId,
                s -> ComponentScriptBindingApplier.matchKey(s.getName()),
                ComponentState::setName, ComponentScriptBindingApplier.temporaryNames(),
                repository::flush);

        entity.getStates().removeIf(existing -> existing.getId() != null
                ? !keptIds.contains(existing.getId())
                : !seenNames.contains(ComponentScriptBindingApplier.matchKey(existing.getName())));
        for (ComponentState target : incoming) {
            if (target.getId() == null) {
                entity.getStates().add(target);
            }
        }
    }

    /**
     * Обработчики событий переехали из {@code image.events} в {@code component_event}
     * (принадлежат компоненту, а не картинке состояния). Ключ вычищается на входе, чтобы
     * у события не появилось второго места хранения: {@code image} — только графика.
     */
    private JsonNode stripEvents(JsonNode image) {
        if (image instanceof ObjectNode object) {
            object.remove("events");
        }
        return image;
    }
}
