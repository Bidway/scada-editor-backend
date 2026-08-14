package com.example.editor.merge;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.model.component.Component;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Set;

/**
 * Обход дерева компонентов, общий для восстановления версии ({@code SceneDocumentSource}) и
 * сохранения сцены целиком ({@code ComponentServiceImpl}): оба вычисляют «что осталось» по
 * присланному/сохранённому дереву dto и убирают из графа сущностей всё, чего там больше нет.
 * <p>
 * Раньше это была пара приватных методов в {@code SceneDocumentSource}; при добавлении удаления
 * непереданного в {@code PUT} (план 3b) понадобился ровно тот же обход. Держать его в двух
 * местах нельзя — риск разъехаться при первой же правке и разъехаться молча, — поэтому он вынесен
 * сюда одной реализацией.
 */
@UtilityClass
public class ComponentTreePruner {

    /** Собирает id всех компонентов дерева (включая вложенные уровни) в {@code into}. */
    public void collectIds(List<ComponentCreateDto> dtos, Set<Long> into) {
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
     * Всё, чего нет в {@code keep}, выбывает из коллекции родителя — {@code orphanRemoval}
     * доделает удаление каскадом. Обход рекурсивный: узел, переживший чистку родителя, может
     * сам держать детей, которых в {@code keep} тоже нет.
     */
    public void pruneObsolete(Component parent, Set<Long> keep) {
        parent.getChildren().removeIf(child -> !keep.contains(child.getId()));
        for (Component child : parent.getChildren()) {
            pruneObsolete(child, keep);
        }
    }
}
