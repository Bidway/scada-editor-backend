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

    @Test
    void rowAddedByMeThatTheyDidNotTouch_isKeptWithoutConflict() {
        List<ComponentCreateDto> base = tree(List.of());
        List<ComponentCreateDto> mine = tree(List.of(script(null, "Стоп", "return 1;")));
        List<ComponentCreateDto> theirs = tree(List.of());

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getScripts()).singleElement()
                .satisfies(script -> assertThat(script.getName()).isEqualTo("Стоп"));
        assertThat(result.changes())
                .as("это моя правка — показывать её мне самому незачем")
                .isEmpty();
    }

    @Test
    void bothDeletedTheRow_isNotAConflict() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of());
        List<ComponentCreateDto> theirs = tree(List.of());

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("согласованное удаление — не конфликт")
                .isTrue();
        assertThat(result.merged().get(0).getScripts()).isEmpty();
    }

    @Test
    void independentIdenticalAddition_isNotDuplicated() {
        List<ComponentCreateDto> base = tree(List.of());
        List<ComponentCreateDto> mine = tree(List.of(script(null, "Стоп", "return 1;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(null, "Стоп", "return 1;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getScripts())
                .as("обе стороны независимо завели одно и то же — строка не задваивается")
                .singleElement()
                .satisfies(script -> assertThat(script.getScript()).isEqualTo("return 1;"));
    }

    /**
     * Регрессия: сырой ввод клиента для нетронутой строки вправе не нести id вовсе — это уже
     * допускает остальной код проекта (сопоставление по имени в ComponentScriptBindingApplier,
     * см. ComponentSaveIT.resave_keepsScriptIds). Раньше строка без id ложилась в мою карту под
     * "name:...", а в карты базы и "их" стороны, где id есть всегда (обе приходят из БД), — под
     * "id:...", и слияние решало, что я её удалил и тут же завёл заново — ложный конфликт
     * DELETED_BY_YOU там, где я строку вообще не трогал.
     */
    @Test
    void theirEditSurvives_whenMyUnchangedRowOmitsId() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(null, "Открыть", "return 1;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 2;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("я строку не трогал и не прислал её id — это не удаление и не новая строка")
                .isTrue();
        assertThat(result.merged().get(0).getScripts().get(0).getScript())
                .as("чужая правка обязана уцелеть")
                .isEqualTo("return 2;");
    }

    /**
     * Симметричный случай: правку без id принёс не я, а посылаю без id. Строка должна
     * сопоставиться с базой/их стороной так же, а не потеряться как «моё новое добавление».
     */
    @Test
    void myEditSurvives_whenISendItWithoutId() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(null, "Открыть", "return 42;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 1;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getScripts()).singleElement()
                .as("моя правка без id не должна задвоиться со строкой из базы")
                .satisfies(script -> assertThat(script.getScript()).isEqualTo("return 42;"));
    }

    /**
     * Поправка не должна прятать настоящий конфликт: если обе стороны правда изменили одну
     * строку по-разному, отсутствие id на моей стороне не превращает это в тихое принятие правки.
     */
    @Test
    void bothModifiedIsStillAConflict_evenWhenIOmitTheId() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(script(null, "Открыть", "return 42;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 55;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isFalse();
        assertThat(result.conflicts()).singleElement()
                .satisfies(conflict -> assertThat(conflict.kind()).isEqualTo(ConflictKind.BOTH_MODIFIED));
    }

    /**
     * Поправка не должна и переусердствовать: моя строка без id, которой нет ни в базе, ни у
     * «них» под тем же именем, — это по-прежнему новое добавление, а не случайное сопоставление
     * с чем-то посторонним.
     */
    @Test
    void myNewRowWithoutId_staysAnAddition() {
        List<ComponentCreateDto> base = tree(List.of(script(10L, "Открыть", "return 1;")));
        List<ComponentCreateDto> mine = tree(List.of(
                script(10L, "Открыть", "return 1;"), script(null, "Стоп", "return 9;")));
        List<ComponentCreateDto> theirs = tree(List.of(script(10L, "Открыть", "return 1;")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getScripts())
                .extracting(ScriptCreateDto::getScript)
                .containsExactlyInAnyOrder("return 1;", "return 9;");
    }

    /**
     * Регрессия на фикс раунда 1 (C1): я в одном запросе шлю и id-строку, и id-less строку с тем
     * же именем — например, переименовал одну и завёл вторую под старым именем. Раньше алиас по
     * имени клал id-less строку в мою карту под тем же ключом {@code "id:5"}, что и id-строка, и
     * обычный {@code Map.put} тихо стирал первую запись второй: строка 5 пропадала из слитого
     * дерева, будто её никто не присылал. Коллизионно-безопасное сопоставление обязано развести
     * их по разным ключам — id-less строка не имеет права отбирать чужой явный id.
     */
    @Test
    void myIdRowAndMyIdLessRowWithSameName_bothSurvive() {
        List<ComponentCreateDto> base = tree(List.of(script(5L, "Открыть", "a();")));
        List<ComponentCreateDto> mine = tree(List.of(
                script(5L, "Открыть", "a();"), script(null, "Открыть", "b();")));
        List<ComponentCreateDto> theirs = tree(List.of(script(5L, "Открыть", "a();")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged().get(0).getScripts())
                .as("явная строка 5 не должна пропасть под весом одноимённой id-less строки")
                .hasSize(2);
        assertThat(result.merged().get(0).getScripts())
                .filteredOn(script -> script.getId() != null)
                .singleElement()
                .satisfies(script -> {
                    assertThat(script.getId()).isEqualTo(5L);
                    assertThat(script.getScript()).isEqualTo("a();");
                });
        assertThat(result.merged().get(0).getScripts())
                .filteredOn(script -> script.getId() == null)
                .singleElement()
                .satisfies(script -> assertThat(script.getScript()).isEqualTo("b();"));
    }
}
