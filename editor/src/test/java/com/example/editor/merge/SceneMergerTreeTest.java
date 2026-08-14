package com.example.editor.merge;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Слияние самого дерева: добавление, удаление и правка компонентов на разной глубине. */
class SceneMergerTreeTest {

    private final SceneMerger merger = new SceneMerger(new ObjectMapper());

    private ComponentCreateDto component(Long id, String name, ComponentCreateDto... children) {
        ComponentCreateDto component = new ComponentCreateDto();
        component.setId(id);
        component.setName(name);
        component.setType("valve");
        component.setChildren(new ArrayList<>(List.of(children)));
        return component;
    }

    private ComponentCreateDto withScript(ComponentCreateDto component, Long id, String body) {
        ScriptCreateDto script = new ScriptCreateDto();
        script.setId(id);
        script.setName("Открыть");
        script.setScript(body);
        component.setScripts(new ArrayList<>(List.of(script)));
        return component;
    }

    @Test
    void componentsAddedByBothSides_areBothKept() {
        List<ComponentCreateDto> base = List.of(component(1L, "Насос"));
        List<ComponentCreateDto> mine = List.of(component(1L, "Насос"), component(null, "Клапан"));
        List<ComponentCreateDto> theirs = List.of(component(1L, "Насос"), component(null, "Задвижка"));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged()).extracting(ComponentCreateDto::getName)
                .containsExactlyInAnyOrder("Насос", "Клапан", "Задвижка");
    }

    @Test
    void changesInDifferentChildren_merge() {
        List<ComponentCreateDto> base = List.of(component(1L, "Группа",
                withScript(component(2L, "Насос"), 10L, "return 1;"),
                withScript(component(3L, "Клапан"), 11L, "return 1;")));
        List<ComponentCreateDto> mine = List.of(component(1L, "Группа",
                withScript(component(2L, "Насос"), 10L, "return 42;"),
                withScript(component(3L, "Клапан"), 11L, "return 1;")));
        List<ComponentCreateDto> theirs = List.of(component(1L, "Группа",
                withScript(component(2L, "Насос"), 10L, "return 1;"),
                withScript(component(3L, "Клапан"), 11L, "return 55;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("правки в разных детях одной группы не пересекаются")
                .isTrue();
        List<ComponentCreateDto> children = result.merged().get(0).getChildren();
        assertThat(children.get(0).getScripts().get(0).getScript()).isEqualTo("return 42;");
        assertThat(children.get(1).getScripts().get(0).getScript()).isEqualTo("return 55;");
    }

    @Test
    void componentDeletedByThemWhileIEditedItsScript_isAConflict() {
        List<ComponentCreateDto> base = List.of(withScript(component(1L, "Насос"), 10L, "return 1;"));
        List<ComponentCreateDto> mine = List.of(withScript(component(1L, "Насос"), 10L, "return 42;"));
        List<ComponentCreateDto> theirs = List.of();

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.conflicts()).isNotEmpty();
        assertThat(result.conflicts().get(0).kind()).isEqualTo(ConflictKind.DELETED_BY_THEM);
        assertThat(result.conflicts().get(0).entity()).isEqualTo("component");
    }

    @Test
    void deepChangeIsFoundOnTheThirdLevel() {
        List<ComponentCreateDto> base = List.of(component(1L, "Проект",
                component(2L, "Группа", withScript(component(3L, "Насос"), 10L, "return 1;"))));
        List<ComponentCreateDto> mine = List.of(component(1L, "Проект",
                component(2L, "Группа", withScript(component(3L, "Насос"), 10L, "return 1;"))));
        List<ComponentCreateDto> theirs = List.of(component(1L, "Проект",
                component(2L, "Группа", withScript(component(3L, "Насос"), 10L, "return 55;"))));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getChildren().get(0).getChildren().get(0)
                .getScripts().get(0).getScript()).isEqualTo("return 55;");
        assertThat(result.changes()).singleElement()
                .satisfies(change -> assertThat(change.path()).isEqualTo("Проект / Группа / Насос / Открыть"));
    }

    @Test
    void bothRenamedTheSameComponentDifferently_isAConflict() {
        List<ComponentCreateDto> base = List.of(component(1L, "Насос"));
        List<ComponentCreateDto> mine = List.of(component(1L, "Насос-1"));
        List<ComponentCreateDto> theirs = List.of(component(1L, "Насос-2"));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict ->
                        assertThat(conflict.kind()).isEqualTo(ConflictKind.BOTH_MODIFIED));
    }

    @Test
    void bothReorderedChildrenDifferently_isAConflict() {
        ComponentCreateDto a = component(2L, "Насос");
        ComponentCreateDto b = component(3L, "Клапан");
        ComponentCreateDto c = component(4L, "Задвижка");
        List<ComponentCreateDto> base = List.of(component(1L, "Группа", a, b, c));
        List<ComponentCreateDto> mine = List.of(component(1L, "Группа", b, a, c));
        List<ComponentCreateDto> theirs = List.of(component(1L, "Группа", c, a, b));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo(ConflictKind.BOTH_MODIFIED);
            assertThat(conflict.entity()).isEqualTo("children_order");
            assertThat(conflict.path()).isEqualTo("Группа");
        });
    }

    @Test
    void reorderByOneSideOnly_isApplied() {
        ComponentCreateDto a = component(2L, "Насос");
        ComponentCreateDto b = component(3L, "Клапан");
        List<ComponentCreateDto> base = List.of(component(1L, "Группа", a, b));
        List<ComponentCreateDto> mine = List.of(component(1L, "Группа", b, a));
        List<ComponentCreateDto> theirs = List.of(component(1L, "Группа", a, b));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("переставил один — спорить не с кем")
                .isTrue();
        assertThat(result.merged().get(0).getChildren())
                .extracting(ComponentCreateDto::getName)
                .containsExactly("Клапан", "Насос");
    }
}
