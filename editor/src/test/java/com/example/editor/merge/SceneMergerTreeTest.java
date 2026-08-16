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

    private ScriptCreateDto script(Long id, String name, String body) {
        ScriptCreateDto script = new ScriptCreateDto();
        script.setId(id);
        script.setName(name);
        script.setScript(body);
        return script;
    }

    private ComponentCreateDto withScripts(ComponentCreateDto component, ScriptCreateDto... scripts) {
        component.setScripts(new ArrayList<>(List.of(scripts)));
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
        MergeConflict conflict = result.conflicts().get(0);
        assertThat(conflict.kind()).isEqualTo(ConflictKind.DELETED_BY_THEM);
        assertThat(conflict.entity()).isEqualTo("component");
        // Спорная правка сидит во вложенном скрипте, а не в скалярных полях компонента —
        // текст конфликта обязан её показать, иначе человек не поймёт, что теряет.
        assertThat(conflict.yours())
                .as("человек должен увидеть свою правку скрипта, а не пустой набор скалярных полей")
                .contains("42");
    }

    @Test
    void componentDeletedByThemWhileIDidNotTouchIt_isClean() {
        List<ComponentCreateDto> base = List.of(withScript(component(1L, "Насос"), 10L, "return 1;"));
        List<ComponentCreateDto> mine = List.of(withScript(component(1L, "Насос"), 10L, "return 1;"));
        List<ComponentCreateDto> theirs = List.of();

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged()).isEmpty();
        assertThat(result.changes()).singleElement()
                .satisfies(change -> assertThat(change.change()).isEqualTo(ChangeKind.DELETED));
    }

    @Test
    void reorderedRowsInsideDeletedComponent_doesNotBlockCleanDeletion() {
        ScriptCreateDto open = script(10L, "Открыть", "return 1;");
        ScriptCreateDto close = script(11L, "Закрыть", "return 1;");
        List<ComponentCreateDto> base = List.of(withScripts(component(1L, "Насос"), open, close));
        List<ComponentCreateDto> mine = List.of(withScripts(component(1L, "Насос"), close, open));
        List<ComponentCreateDto> theirs = List.of();

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("строки сопоставляются по id, а не по позиции — перестановка не правка")
                .isTrue();
        assertThat(result.merged()).isEmpty();
        assertThat(result.changes()).singleElement()
                .satisfies(change -> assertThat(change.change()).isEqualTo(ChangeKind.DELETED));
    }

    @Test
    void myRowDeletionIsAbsorbedByTheirComponentDeletion() {
        List<ComponentCreateDto> base = List.of(withScript(component(1L, "Насос"), 10L, "return 1;"));
        List<ComponentCreateDto> mine = List.of(component(1L, "Насос"));
        List<ComponentCreateDto> theirs = List.of();

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("удаление скрипта поглощается удалением всего компонента — спорить не с чем")
                .isTrue();
        assertThat(result.merged()).isEmpty();
        assertThat(result.changes()).singleElement()
                .satisfies(change -> assertThat(change.change()).isEqualTo(ChangeKind.DELETED));
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

    @Test
    void reorderByThemOnly_isApplied() {
        ComponentCreateDto a = component(2L, "Насос");
        ComponentCreateDto b = component(3L, "Клапан");
        List<ComponentCreateDto> base = List.of(component(1L, "Группа", a, b));
        List<ComponentCreateDto> mine = List.of(component(1L, "Группа", a, b));
        List<ComponentCreateDto> theirs = List.of(component(1L, "Группа", b, a));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("переставили только они — спорить не с кем, а их порядок не должен потеряться")
                .isTrue();
        assertThat(result.merged().get(0).getChildren())
                .extracting(ComponentCreateDto::getName)
                .containsExactly("Клапан", "Насос");
    }

    @Test
    void bothReorderedTopLevelComponentsDifferently_isAConflict() {
        ComponentCreateDto a = component(1L, "Насос");
        ComponentCreateDto b = component(2L, "Клапан");
        ComponentCreateDto c = component(3L, "Задвижка");
        List<ComponentCreateDto> base = List.of(a, b, c);
        List<ComponentCreateDto> mine = List.of(b, a, c);
        List<ComponentCreateDto> theirs = List.of(c, a, b);

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo(ConflictKind.BOTH_MODIFIED);
            assertThat(conflict.entity()).isEqualTo("children_order");
        });
    }

    /**
     * Регрессия на фикс раунда 1 (C1): в теле запроса я присылаю и существующий компонент 5 без
     * изменений, и новый компонент с тем же именем без id — например, переименовал старый и
     * завёл второй под освободившимся именем в одном сохранении. Раньше алиас по имени клал
     * id-less компонент в мою карту под тем же ключом {@code "id:5"}, что и явный, обычный
     * {@code Map.put} тихо стирал первую запись — компонент 5 пропадал из слитого дерева и
     * {@code deleteMissing} на стороне сервиса удалил бы его вместе со всем поддеревом, хотя
     * клиент его прислал и получил бы 200.
     */
    @Test
    void myIdComponentAndMyIdLessComponentWithSameName_bothSurvive() {
        List<ComponentCreateDto> base = List.of(component(5L, "Насос"));
        List<ComponentCreateDto> mine = List.of(component(5L, "Насос"), component(null, "Насос"));
        List<ComponentCreateDto> theirs = List.of(component(5L, "Насос"));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged())
                .as("оригинал не должен пропасть под весом одноимённого id-less добавления")
                .hasSize(2);
        assertThat(result.merged())
                .filteredOn(c -> c.getId() != null)
                .singleElement()
                .satisfies(c -> assertThat(c.getId()).isEqualTo(5L));
        assertThat(result.merged())
                .filteredOn(c -> c.getId() == null)
                .hasSize(1);
    }

    /**
     * Регрессия на фикс раунда 1 (I2): я пересылаю нетронутый компонент без id (обычное дело для
     * сырого ввода клиента — см. {@code nameAlias}), а «они» в это время переставили детей.
     * {@code applyOrder} ищет объект в списке порядка по его собственному id; раньше объект
     * оставался id-less и в списке не находился — сортировка молча откидывала его в конец,
     * несмотря на то что список порядка (посчитанный через алиас) содержал верную позицию.
     */
    @Test
    void reorderByThemOnly_isAppliedEvenWhenIOmitAnId() {
        ComponentCreateDto a = component(2L, "Насос");
        ComponentCreateDto b = component(3L, "Клапан");
        List<ComponentCreateDto> base = List.of(a, b);
        List<ComponentCreateDto> mine = List.of(component(null, "Насос"), component(3L, "Клапан"));
        List<ComponentCreateDto> theirs = List.of(component(3L, "Клапан"), component(2L, "Насос"));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean())
                .as("переставили только они — спорить не с кем, а моё отсутствие id не мешает")
                .isTrue();
        assertThat(result.merged())
                .extracting(ComponentCreateDto::getName)
                .as("их порядок обязан примениться, а не откинуть безымянный объект в конец")
                .containsExactly("Клапан", "Насос");
        assertThat(result.merged().get(1).getId())
                .as("сопоставленная по алиасу идентичность обязана перенестись на объект")
                .isEqualTo(2L);
    }

    /**
     * I-3 (найдено финальным ревью ветки): имена компонентов не уникальны — ни ограничения в
     * базе, ни проверки на записи. Две мои новые строки с одним именем считали одинаковый
     * {@code nameKey}, и обычный {@code Map.put} во втором проходе {@code byKey} тихо стирал
     * первую второй: один из двух добавленных «Насосов» пропадал вместе со всем поддеревом, а
     * клиент получал 200. Коллизионная защита, которая уже была на ветке с алиасом, обязана
     * работать и на голом имени.
     */
    @Test
    void twoOfMyNewComponentsWithTheSameName_bothSurvive() {
        List<ComponentCreateDto> base = List.of();
        List<ComponentCreateDto> mine = List.of(
                withScripts(component(null, "Насос"), script(null, "Открыть", "первый();")),
                withScripts(component(null, "Насос"), script(null, "Открыть", "второй();")));
        List<ComponentCreateDto> theirs = List.of();

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.isClean()).isTrue();
        assertThat(result.merged())
                .as("два одноимённых добавления — две строки, а не одна затёртая другой")
                .hasSize(2);
        assertThat(result.merged())
                .flatExtracting(ComponentCreateDto::getScripts)
                .extracting(ScriptCreateDto::getScript)
                .containsExactlyInAnyOrder("первый();", "второй();");
    }

    /**
     * I-3, вторая половина: {@code addAlias} складывал имена обычным {@code put}, и при двух
     * одноимённых строках алиас указывал на ту, чей id попался последним. Здесь «они» завели
     * второго «Насоса» рядом с существующим, а я правлю существующего, не переслав его id
     * (законный сырой ввод — см. {@code nameAlias}). Алиас вёл на их новый компонент, и моя
     * правка молча уезжала в него: чужое добавление затиралось моим содержимым, а старый
     * компонент удалялся — всё это с ответом 200. Имя, за которое держатся два id, перестаёт
     * быть адресом: алиас на него не выдаётся вовсе, и моя строка остаётся тем, чем выглядит, —
     * новой.
     */
    @Test
    void myIdLessEdit_doesNotLandOnTheirSameNamedAddition() {
        List<ComponentCreateDto> base = List.of(
                withScripts(component(5L, "Насос"), script(10L, "Открыть", "было();")));
        List<ComponentCreateDto> mine = List.of(
                withScripts(component(null, "Насос"), script(null, "Открыть", "МОЁ();")));
        List<ComponentCreateDto> theirs = List.of(
                withScripts(component(5L, "Насос"), script(10L, "Открыть", "было();")),
                withScripts(component(6L, "Насос"), script(11L, "Открыть", "ИХ();")));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.merged())
                .filteredOn(component -> Long.valueOf(6L).equals(component.getId()))
                .as("их новый компонент обязан уцелеть — я о нём даже не знал")
                .singleElement()
                .satisfies(component -> assertThat(component.getScripts().get(0).getScript())
                        .isEqualTo("ИХ();"));
        assertThat(result.merged())
                .flatExtracting(ComponentCreateDto::getScripts)
                .extracting(ScriptCreateDto::getScript)
                .as("моя правка обязана остаться моей, а не подмениться чужой строкой")
                .contains("МОЁ();");
    }
}
