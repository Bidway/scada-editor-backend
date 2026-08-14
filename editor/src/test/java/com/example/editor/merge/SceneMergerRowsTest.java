package com.example.editor.merge;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Слияние строк внутри одного компонента. Юнит-тесты без Spring: ядро — чистая функция, и
 * поднимать ради него Postgres незачем.
 * <p>
 * Во всех тестах база — компонент «Насос» с одним скриптом «Открыть», а расходятся стороны
 * уже от неё.
 */
class SceneMergerRowsTest {

    private final SceneMerger merger = new SceneMerger(new ObjectMapper());

    private ScriptCreateDto script(Long id, String name, String body) {
        ScriptCreateDto script = new ScriptCreateDto();
        script.setId(id);
        script.setName(name);
        script.setScript(body);
        return script;
    }

    private ComponentCreateDto pump(List<ScriptCreateDto> scripts) {
        ComponentCreateDto pump = new ComponentCreateDto();
        pump.setId(1L);
        pump.setName("Насос");
        pump.setType("valve");
        pump.setScripts(new ArrayList<>(scripts));
        return pump;
    }

    private List<ComponentCreateDto> tree(List<ScriptCreateDto> scripts) {
        return List.of(pump(scripts));
    }

    @Test
    void theirChangeIsTakenWhenIDidNotTouchTheRow() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 2;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getScripts().get(0).getScript())
                .as("я строку не трогал — берётся чужая правка")
                .isEqualTo("return 2;");
    }

    @Test
    void myChangeIsKeptWhenTheyDidNotTouchTheRow() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(10L, "Открыть", "return 42;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 1;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getScripts().get(0).getScript()).isEqualTo("return 42;");
    }

    @Test
    void bothChangedTheSameRow_isAConflict() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(10L, "Открыть", "return 42;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 55;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isFalse();
        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict -> {
                    assertThat(conflict.kind()).isEqualTo(ConflictKind.BOTH_MODIFIED);
                    assertThat(conflict.entity()).isEqualTo("script");
                    assertThat(conflict.path()).isEqualTo("Насос / Открыть");
                });
    }

    @Test
    void identicalChangeOnBothSides_isNotAConflict() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(10L, "Открыть", "return 42;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 42;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("оба сделали одно и то же — спорить не о чем")
                .isTrue();
        assertThat(result.merged().get(0).getScripts().get(0).getScript()).isEqualTo("return 42;");
    }

    @Test
    void rowAddedByThem_arrivesInTheResult() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> theirs = tree(List.of(
                script(10L, "Открыть", "return 1;"), script(11L, "Закрыть", "return 2;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getScripts()).hasSize(2);
    }

    @Test
    void rowDeletedByThemWhileIEditedIt_isAConflict() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(10L, "Открыть", "return 42;")));
        List<ComponentCreateDto> theirs = tree(List.of());

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict ->
                        assertThat(conflict.kind()).isEqualTo(ConflictKind.DELETED_BY_THEM));
    }

    @Test
    void rowDeletedByThemThatIDidNotTouch_staysDeleted() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> theirs = tree(List.of());

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getScripts()).isEmpty();
    }

    @Test
    void rowDeletedByMeWhileTheyEditedIt_isAConflict() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of());
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 55;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict ->
                        assertThat(conflict.kind()).isEqualTo(ConflictKind.DELETED_BY_YOU));
    }

    @Test
    void sameNameAddedByBothWithDifferentBody_isAConflict() {
        List<ComponentCreateDto> base = tree(List.of());
        List<ComponentCreateDto> mine = tree(List.of(script(null, "Стоп", "return 1;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(null, "Стоп", "return 2;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict ->
                        assertThat(conflict.kind()).isEqualTo(ConflictKind.BOTH_ADDED));
    }

    @Test
    void renameByIdOnOneSide_isNotSeenAsDeletePlusAdd() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(10L, "Открыть клапан", "return 1;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 1;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("сопоставление по id — ради этого случая слияние вообще возможно")
                .isTrue();
        assertThat(result.merged().get(0).getScripts()).singleElement()
                .satisfies(script -> assertThat(script.getName()).isEqualTo("Открыть клапан"));
    }
}
