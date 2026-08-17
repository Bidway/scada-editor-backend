package com.example.editor.service.Impl;

import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.mapper.ComponentPropertyMapper;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.recipe.RecipeValueRepository;
import com.example.editor.service.ComponentPropertyService;
import com.example.editor.service.component.SceneRootResolver;
import com.example.editor.service.version.DocumentVersionService;
import com.example.editor.service.version.SceneDocumentSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComponentPropertyServiceImpl implements ComponentPropertyService {

    private final ComponentPropertyRepository repository;
    private final RecipeValueRepository recipeValueRepository;
    private final ComponentPropertyMapper mapper;
    private final DocumentVersionService versionService;
    private final SceneDocumentSource sceneDocumentSource;

    @Override
    @Transactional
    public PropertyResponseDto create(PropertyCreateDto dto, String userName) {
        dto.setName(normalize(dto.getName()));
        ComponentProperty entity = mapper.toEntity(dto);
        Component component = entity.getComponent();
        Long componentId = component == null ? null : component.getId();
        if (componentId != null && repository.existsByComponentIdAndName(componentId, entity.getName())) {
            throw new IllegalStateException(
                    "Property name '" + entity.getName() + "' already exists in component " + componentId
                            + "; names must be unique within a component");
        }
        Long sceneId = SceneRootResolver.sceneRootIdOf(component);
        requireBase(sceneId, dto.getBased_on_version());

        PropertyResponseDto response = mapper.toDto(repository.save(entity));

        snapshot(sceneId, userName, dto.getBased_on_version());
        return response;
    }

    /**
     * Точечное обновление свойства — единственное место, где значения набора переносятся на
     * новое имя строки: здесь на руках сразу и старое имя, и id, и перенос делается тут же,
     * через {@code recipeValueRepository.renameRow}.
     * <p>
     * Массовый путь ({@code ComponentScriptBindingApplier.applyProperties}) с 14.08.2026 тоже
     * распознаёт переименование, если прислан {@code id}, — строка больше не пересоздаётся
     * (см. {@code applyProperties}). Но значения набора он не переносит: меняется только
     * {@code ComponentProperty.name}, {@code recipe_value.row_name} остаётся прежним, а сам
     * перенос лежит вне аудита {@code recipe_change} (заведено отдельно, {@code scada-v3g}).
     * Поэтому для переноса значений набора по-прежнему нужен именно этот эндпоинт —
     * {@code PUT /api/editor/properties/{id}}.
     */
    @Override
    @Transactional
    public PropertyResponseDto update(Long id, PropertyCreateDto dto, String userName) {
        ComponentProperty existing = repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Property not found: " + id));
        Component component = existing.getComponent();
        Long componentId = component == null ? null : component.getId();
        String oldName = existing.getName();

        String newName = normalize(dto.getName());
        dto.setName(newName);
        if (newName != null && componentId != null
                && repository.existsByComponentIdAndNameAndIdNot(componentId, newName, id)) {
            throw new IllegalStateException(
                    "Property name '" + newName + "' already exists in component " + componentId
                            + "; names must be unique within a component");
        }
        Long sceneId = SceneRootResolver.sceneRootIdOf(component);
        requireBase(sceneId, dto.getBased_on_version());

        mapper.updateEntity(dto, existing);
        PropertyResponseDto response = mapper.toDto(repository.save(existing));

        if (newName != null && componentId != null && !newName.equals(oldName)) {
            int moved = recipeValueRepository.renameRow(componentId, oldName, newName);
            if (moved > 0) {
                log.info("Renamed row '{}' -> '{}' in component {}: {} value(s) moved to the new name",
                        oldName, newName, componentId, moved);
            }
        }
        snapshot(sceneId, userName, dto.getBased_on_version());
        return response;
    }

    @Override
    @Transactional
    public void delete(Long id, String userName, Integer basedOnVersion) {
        ComponentProperty existing = repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Property not found: " + id));
        Long sceneId = SceneRootResolver.sceneRootIdOf(existing.getComponent());
        requireBase(sceneId, basedOnVersion);

        repository.deleteById(id);
        snapshot(sceneId, userName, basedOnVersion);
    }

    /** Имя — ключ привязки значений набора, поэтому хранится без краевых пробелов. */
    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Гард версии. Свойство вне сцены (сегодня такого не бывает: свойства висят на компонентах,
     * а те живут под сценой) версионируемого документа не имеет — проверять нечего.
     */
    private void requireBase(Long sceneId, Integer basedOnVersion) {
        if (sceneId != null) {
            versionService.requireBase(DocumentType.SCENE, sceneId, basedOnVersion);
        }
    }

    /**
     * Снимок сцены после правки свойства. Всегда {@code MANUAL}: автосохранение этим путём не
     * ходит, правка свойства — всегда действие человека.
     * <p>
     * {@code flush()} обязателен перед {@code contentOf}: тот лениво подгружает дерево сцены, и
     * без явного сброса удаление свойства до него не доедет — снимок получится с уже удалённой
     * строкой. Та же причина, по которой флашится удаление компонентов.
     */
    private void snapshot(Long sceneId, String userName, Integer basedOnVersion) {
        if (sceneId == null) {
            return;
        }
        repository.flush();
        versionService.record(DocumentType.SCENE, sceneId, sceneDocumentSource.contentOf(sceneId),
                userName, VersionKind.MANUAL, null, basedOnVersion);
    }
}
