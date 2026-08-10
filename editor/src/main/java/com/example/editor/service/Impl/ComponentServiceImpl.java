package com.example.editor.service.Impl;

import com.example.editor.command.component.*;
import com.example.editor.config.command.CommandManager;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.component.ComponentStateDto;
import com.example.editor.dto.project.ProjectCreateDto;
import com.example.editor.dto.project.ProjectCreateResponseDto;
import com.example.editor.dto.project.ProjectsResponseDto;
import com.example.editor.dto.scene.SceneCreateDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.dto.scene.ScenesResponseDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentState;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.component.ComponentRepository;
import com.example.editor.service.ComponentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    @Override
    public List<ComponentResponseDto> create(List<ComponentCreateDto> dtos, String userName) {
        List<Component> prepared = dtos.stream().map(dto -> buildComponent(dto, null)).toList();
        return commandManager.execute(new CreateComponentCommand(repository, prepared, componentMapper, mapper, userName));
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

    @Override
    public List<ComponentResponseDto> update(List<ComponentCreateDto> dtos, String userName) {
        List<Component> prepared = dtos.stream().map(dto -> updateComponent(dto)).toList();
        return commandManager.execute(new UpdateComponentCommand(repository, prepared, componentMapper, mapper, userName));
    }

    @Override
    public void delete(List<Long> ids, String userName) {
        commandManager.execute(new DeleteComponentCommand(repository, ids, userName, mapper));
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

    private Component updateComponent(ComponentCreateDto dto) {
        Component entity = repository.findById(dto.getId())
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

        ComponentScriptBindingApplier.applyProperties(entity, dto);
        ComponentScriptBindingApplier.apply(entity, dto, propertyRepository);
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
        for (ComponentState existing : entity.getStates()) {
            existingByName.put(existing.getName(), existing);
        }

        List<ComponentState> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (ComponentStateDto s : dto.getStates()) {
            if (!seenNames.add(s.getName())) {
                throw new IllegalStateException(
                        "Duplicate state name '" + s.getName() + "' in component " + entity.getId()
                                + "; setState() addresses states by name, so names must be unique");
            }
            ComponentState target = existingByName.get(s.getName());
            if (target == null) {
                target = new ComponentState();
                target.setComponent(entity);
                target.setName(s.getName());
            }
            target.setImage(stripEvents(s.getImage()));
            target.setIsDefault(s.getIsDefault());
            incoming.add(target);
        }

        entity.getStates().removeIf(existing -> !seenNames.contains(existing.getName()));
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
