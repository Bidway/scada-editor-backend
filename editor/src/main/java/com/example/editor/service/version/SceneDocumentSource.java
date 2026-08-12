package com.example.editor.service.version;

import com.example.editor.exception.NotFoundException;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.model.version.DocumentType;
import com.example.editor.repository.component.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SceneDocumentSource implements DocumentSource {

    private final ComponentRepository componentRepository;
    private final ComponentMapper componentMapper;
    private final ObjectMapper objectMapper;

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

    @Override
    public void restore(Long sceneId, JsonNode content, String userName) {
        throw new UnsupportedOperationException("Восстановление сцены — задача 7");
    }
}
