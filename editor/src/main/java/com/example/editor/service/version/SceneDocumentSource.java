package com.example.editor.service.version;

import com.example.editor.dto.component.BindingPayloadDto;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentStateDto;
import com.example.editor.dto.component.EventPayloadDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.model.component.Binding;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentEvent;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.model.component.ComponentState;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.model.component.Script;
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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

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

        // Компонента (и вложенной строки), удалённых после снимка, в базе больше нет — id
        // снимаем, они создадутся заново. Свой прежний id они не вернут, но альтернатива —
        // падение всего восстановления.
        dropMissingIds(children);

        for (ComponentCreateDto child : children) {
            child.setParent_id(sceneId);
        }
        componentService.update(children, userName, VersionKind.RESTORE, null);
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

    /**
     * Снимает id, которых в базе больше нет, — и у компонентов, и у вложенных строк.
     * <p>
     * Вложенные id раньше сюда не доезжали: в снимке (это сериализованный
     * {@code ComponentResponseDto}) они лежали всегда, но у DTO сохранения поля {@code id} не
     * было, и Jackson выбрасывал их молча. Как только поле появилось, исторический номер строки,
     * удалённой после снимка, поехал прямо в аплаер, тот не нашёл её среди строк компонента и
     * завалил всё восстановление ошибкой «does not belong to component».
     * <p>
     * Снимать вложенные id безусловно (доветочное поведение) нельзя: тогда каждое
     * восстановление пересоздаёт все строки, а {@code Script.id} — это {@code scriptId} в уже
     * открытых сессиях мониторинга. Поэтому сверяемся построчно со строками того самого
     * компонента: набор проверки в точности тот же, по которому аплаер потом ищет цель.
     */
    private void dropMissingIds(List<ComponentCreateDto> dtos) {
        for (ComponentCreateDto dto : dtos) {
            Component existing = null;
            if (dto.getId() != null) {
                existing = componentRepository.findById(dto.getId()).orElse(null);
                if (existing == null) {
                    dto.setId(null);
                }
            }
            dropMissingNestedIds(dto, existing);
            if (dto.getChildren() != null) {
                dropMissingIds(dto.getChildren());
            }
        }
    }

    /**
     * Ссылка биндинга на свойство — не id строки, а указатель на соседнюю, и протухает она
     * отдельно от собственного id свойства (тот снимает {@code dropUnknownIds} выше — так же,
     * как у скриптов, состояний, событий и биндингов). Свойство, удалённое после снимка,
     * восстановление создаёт заново <b>с новым id</b>, поэтому номер из снимка адресует пустоту
     * и валит всё восстановление в 400 (scada-3hw).
     * <p>
     * Снимаем номер только вместе с именем на замену: у снимков, записанных до появления
     * {@code component_property_name}, подменить его нечем, и осмысленная ошибка «свойство N не
     * найдено» полезнее невнятной «биндингу нужен id или имя». Такой снимок восстановится, как
     * только сцену сохранят заново.
     */
    private void dropDeadPropertyRefs(ComponentCreateDto dto, Component existing) {
        if (dto.getBindings() == null) {
            return;
        }
        Set<Long> known = idsOf(existing == null ? null : existing.getProperties(),
                ComponentProperty::getId);
        for (BindingPayloadDto binding : dto.getBindings()) {
            Long propertyId = binding.getComponent_property_id();
            boolean hasName = binding.getComponent_property_name() != null
                    && !binding.getComponent_property_name().isBlank();
            if (propertyId != null && !known.contains(propertyId) && hasName) {
                binding.setComponent_property_id(null);
            }
        }
    }

    /** Компонента нет — значит, нет и ни одной его строки: id снимаются все. */
    private void dropMissingNestedIds(ComponentCreateDto dto, Component existing) {
        dropUnknownIds(dto.getScripts(), ScriptCreateDto::getId, ScriptCreateDto::setId,
                idsOf(existing == null ? null : existing.getScripts(), Script::getId));
        dropUnknownIds(dto.getStates(), ComponentStateDto::getId, ComponentStateDto::setId,
                idsOf(existing == null ? null : existing.getStates(), ComponentState::getId));
        dropUnknownIds(dto.getEvents(), EventPayloadDto::getId, EventPayloadDto::setId,
                idsOf(existing == null ? null : existing.getEvents(), ComponentEvent::getId));
        dropUnknownIds(dto.getBindings(), BindingPayloadDto::getId, BindingPayloadDto::setId,
                idsOf(existing == null ? null : existing.getBindings(), Binding::getId));
        dropUnknownIds(dto.getProperties(), PropertyCreateDto::getId, PropertyCreateDto::setId,
                idsOf(existing == null ? null : existing.getProperties(), ComponentProperty::getId));
        dropDeadPropertyRefs(dto, existing);
    }

    private <T> void dropUnknownIds(List<T> incoming, Function<T, Long> idOf,
                                    BiConsumer<T, Long> setId, Set<Long> known) {
        if (incoming == null) {
            return;
        }
        for (T item : incoming) {
            Long id = idOf.apply(item);
            if (id != null && !known.contains(id)) {
                setId.accept(item, null);
            }
        }
    }

    private <E> Set<Long> idsOf(Collection<E> rows, Function<E, Long> idOf) {
        Set<Long> ids = new HashSet<>();
        if (rows == null) {
            return ids;
        }
        for (E row : rows) {
            Long id = idOf.apply(row);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }
}
