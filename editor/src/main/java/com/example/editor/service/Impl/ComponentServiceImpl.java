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
     * <p>
     * Снимок в конце берётся по {@code scene_id} из конверта, а <b>не</b> по сценам, вычисленным
     * из записанных сущностей ({@link #snapshotScenesOf}, как в {@link #create}). У пустого
     * {@code PUT} записывать нечего: множество сцен выходило пустым, {@link #snapshotScenes} не
     * делал ни одного витка, {@code record} не звался — сцена стиралась, клиент получал 200 с
     * {@code version_no: null}, а последняя версия в истории продолжала описывать полную сцену
     * (C-1). Восстанавливаться после такого было не из чего, и разъезжались живое состояние с
     * последней версией — тот самый инвариант, на котором стоит {@code SceneMergeService.merge},
     * когда берёт «чужое» из живого состояния, а не из снимка.
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
            requireSceneMembership(dtos, sceneId);
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
        // Сцена из конверта попадает в снимок всегда, а не только если в неё что-то записалось:
        // см. javadoc метода про пустой PUT (C-1). Сцены записанного добавляются следом ради
        // восстановления версии — оно зовёт update без scene_id, и там их больше взять негде.
        Set<Long> snapshotTargets = new LinkedHashSet<>();
        if (sceneId != null) {
            snapshotTargets.add(sceneId);
        }
        snapshotTargets.addAll(scenesOf(prepared));
        Integer versionNo = snapshotScenes(snapshotTargets, userName, kind, basedOnVersion);
        return new ComponentSaveResponseDto(mapper.valueToTree(response), versionNo, null,
                mergedReport(outcome));
    }

    /**
     * Блок {@code merged} контракта; {@code null}, только если слияния не было вовсе.
     * <p>
     * Пустой {@code changes} блок не отменяет (I-5): так выглядит слияние, где обе стороны
     * сделали одну и ту же правку — показывать в отчёте нечего, но клиенту всё равно важно
     * узнать, что его база устарела и с какой версией он в итоге слился. Раньше он получал в
     * этом случае обычный 200 и не узнавал ни того, ни другого.
     */
    private Map<String, Object> mergedReport(SceneMergeService.MergeOutcome outcome) {
        if (outcome == null) {
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
     * Всё присланное обязано принадлежать названной сцене — и то, что правится ({@code id}), и
     * то, куда оно кладётся ({@code parent_id}).
     * <p>
     * До плана 3b гард версии выводил набор сцен из тела ({@link #requireBaseForScenesOf}) и
     * потому накрывал каждую задетую сцену. Теперь гардится ровно одна — та, что в {@code
     * scene_id}, — а {@code updateComponent} по-прежнему берёт родителя из {@code parent_id}
     * каждого dto и ничьей принадлежности не проверяет. Тело с {@code scene_id: A}, несущее
     * компонент сцены B, правило бы B без проверки версии и без слияния, вычищало бы A целиком
     * (детей A в теле нет) и записывало бы сцене B версию с {@code based_on_version} от A (I-1).
     * <p>
     * Обход вверх — тот же {@link #sceneRootIdOf}, что и у гарда версии: второй копии обхода
     * дерева здесь заводить нельзя, они разойдутся. Нерезолвимый id не наша забота: строки нет,
     * сцены у неё тоже нет, и ошибку про неё выдаст {@code updateComponent} — так же, как до
     * ветки.
     * <p>
     * Побочно это запрещает переезд компонента между сценами одним {@code PUT}. Он и не был
     * выразим: тело — состав <b>одной</b> сцены целиком, и сцена-источник в нём не описана, так
     * что «переезд» неотличим от «правлю чужую сцену вслепую».
     */
    private void requireSceneMembership(List<ComponentCreateDto> dtos, Long sceneId) {
        for (ComponentCreateDto dto : dtos) {
            requireInScene(dto.getId(), sceneId, "component");
            requireInScene(dto.getParent_id(), sceneId, "parent");
        }
    }

    private void requireInScene(Long componentId, Long sceneId, String role) {
        if (componentId == null) {
            return;
        }
        Component existing = repository.findById(componentId).orElse(null);
        if (existing == null) {
            return;
        }
        Long root = sceneRootIdOf(existing);
        if (!sceneId.equals(root)) {
            throw new IllegalArgumentException(
                    role + " " + componentId + " belongs to scene " + root + ", not to scene_id "
                            + sceneId + ": PUT carries one scene, so a foreign component would be"
                            + " written unguarded while the named scene is wiped");
        }
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
     * <p>
     * <b>Команда здесь сознательно не пишется</b> — при том, что CLAUDE.md требует Command
     * Pattern для изменений данных в {@code editor}, а эндпоинт {@code DELETE} строку в
     * {@code command_log} пишет. Это решение, а не недосмотр (scada-g39):
     * <ul>
     *   <li>каждый {@code PUT} и без того кладёт в {@code document_version} снимок <b>всей</b>
     *       сцены (см. javadoc {@link #update} про C-1) — для эндпоинта со семантикой «вот сцена
     *       целиком» это и есть верная гранулярность восстановления, притом более мелкая, чем
     *       дал бы здесь {@code command_log}: по снимку сцена возвращается вся, а не по одному
     *       компоненту;</li>
     *   <li>отмена на уровне компонента сегодня не работает (scada-8sc) и работать не будет:
     *       решением владельца {@code editor} уходит с Command Pattern на версии документов, и
     *       {@code command_log} этой схемы удаляется целиком. Команда добавила бы запись,
     *       которую некому прочитать ни сейчас, ни потом;</li>
     *   <li>{@link SceneDocumentSource#restore} чистит ровно тем же приёмом и тоже без команды —
     *       два разных поведения на один приём были бы хуже одного согласованного.</li>
     * </ul>
     * Записано здесь потому, что два пути удаления с разным поведением аудита обязаны быть
     * решением на бумаге, а не случайностью, которую следующий читатель примет за забытый вызов.
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
            Component component = repository.findById(id).orElse(null);
            if (component == null) {
                // id не резолвится: компонент уже удалён (кем-то ещё, например) или никогда не
                // существовал. Гарда для него нет и не может быть — без строки нечего сверять
                // по версии. DELETE по такому id сегодня тихо ничего не делает (deleteById не
                // находит строку) и отвечает 200 без всякой проверки — известная дыра, не эта
                // задача её чинит (scada-crk).
                continue;
            }
            Long sceneId = sceneRootIdOf(component);
            if (sceneId == null) {
                // Компонент есть, но не под сценой — сегодня единственный такой случай: сам
                // проект (type=PROJECT, parent=null). У проекта нет версионируемого документа
                // (DocumentType знает только SCENE и TEMPLATE), поэтому гард версии и снимок
                // истории здесь сознательно пропускаются, а не забыты по недосмотру — заведено
                // отдельно, каскадное удаление сцен внутри проекта тоже проходит без проверки
                // и без снимка (scada-69s).
                continue;
            }
            sceneIds.add(sceneId);
        }
        for (Long sceneId : sceneIds) {
            versionService.requireBase(DocumentType.SCENE, sceneId, basedOnVersion);
        }
        commandManager.execute(new DeleteComponentCommand(repository, ids, userName, mapper));
        // Флаш обязателен здесь: DeleteComponentCommand удаляет через repository.deleteById,
        // в обход графа сущностей (в отличие от deleteMissing/restore, которые чистят через
        // orphanRemoval у живого родителя). Пока удаление не сброшено в базу, ниже
        // sceneDocumentSource.contentOf(sceneId) лениво подгружает scene.children тем же
        // запросом впервые за транзакцию — и, не будь явного флаша, отложенный DELETE рискует
        // проиграть гонку каскаду cascade=ALL: Hibernate видит компонент снова в живой коллекции
        // родителя и на очередном флаше воскрешает его вместо удаления (тот же эффект, от
        // которого предостерегает комментарий в SceneDocumentSource.restore, только здесь на
        // входе, а не на выходе). Явный flush() ставит DELETE в базу до этого чтения.
        repository.flush();
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
        return snapshotScenes(scenesOf(saved), userName, kind, basedOnVersion);
    }

    /** Сцены, которых коснулись записанные сущности; для {@code update} — см. {@link #update}. */
    private Set<Long> scenesOf(List<Component> saved) {
        Set<Long> sceneIds = new LinkedHashSet<>();
        for (Component component : saved) {
            Long sceneId = sceneRootIdOf(component);
            if (sceneId != null) {
                sceneIds.add(sceneId);
            }
        }
        return sceneIds;
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
