package com.example.editor.merge;

import com.example.editor.dto.component.ComponentCreateDto;

import java.util.List;

/**
 * Результат слияния.
 * <p>
 * Конфликты и слитое дерево взаимоисключающи по смыслу: при непустом списке конфликтов дерево
 * недостоверно и записывать его нельзя. Отдельного «частично слитого» состояния нет намеренно —
 * контракт обещает клиенту либо сохранение целиком, либо отказ целиком.
 */
public record SceneMerge(List<ComponentCreateDto> merged,
                         List<MergeConflict> conflicts,
                         List<MergeChange> changes) {

    public boolean isClean() {
        return conflicts.isEmpty();
    }
}
