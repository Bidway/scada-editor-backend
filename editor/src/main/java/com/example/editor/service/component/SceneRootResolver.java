package com.example.editor.service.component;

import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentTypes;
import lombok.experimental.UtilityClass;

/**
 * Поиск сцены, которой принадлежит компонент: поднимаемся по родителям до первого узла с
 * типом scene.
 * <p>
 * Вынесен из приватных методов {@code ComponentServiceImpl} ради второго вызывающего —
 * {@code ComponentPropertyServiceImpl} ищет сцену для снимка версии тем же обходом. Раньше
 * это был один приватный метод, и второму месту пришлось бы его продублировать.
 * <p>
 * {@code null} означает «компонент не под сценой». Сегодня такой ровно один — сам проект
 * ({@code type=PROJECT}, {@code parent=null}): у него нет версионируемого документа
 * ({@code DocumentType} знает только SCENE и TEMPLATE), поэтому вызывающие пропускают для
 * него и гард версии, и снимок (scada-69s).
 * <p>
 * Связь {@code parent} ленивая: подниматься по ней можно только пока открыта сессия. Вызывать
 * резолвер вне транзакции — получить {@code LazyInitializationException} на первом же шаге вверх.
 */
@UtilityClass
public class SceneRootResolver {

    public Long sceneRootIdOf(Component component) {
        Component current = component;
        while (current != null) {
            if (ComponentTypes.SCENE.equals(current.getType())) {
                return current.getId();
            }
            current = current.getParent();
        }
        return null;
    }
}
