package com.example.editor.merge;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Отчёт {@code merged.changes} — то, что увидит человек после чужого слияния.
 * <p>
 * Требование к нему одно и оно жёсткое: там перечислено ровно то, что подмешалось с чужой
 * стороны. Мои собственные правки в отчёт попадать не должны — иначе инженер каждый раз читает
 * список того, что и так сделал сам, и перестаёт его читать вообще.
 */
class SceneMergerReportTest {

    private final SceneMerger merger = new SceneMerger(new ObjectMapper());

    private ComponentCreateDto pump(String name, Long scriptId, String body) {
        ComponentCreateDto pump = new ComponentCreateDto();
        pump.setId(1L);
        pump.setName(name);
        pump.setType("valve");
        ScriptCreateDto script = new ScriptCreateDto();
        script.setId(scriptId);
        script.setName("Открыть");
        script.setScript(body);
        pump.setScripts(new ArrayList<>(List.of(script)));
        return pump;
    }

    @Test
    void myOwnChangesAreNotReported() {
        List<ComponentCreateDto> base = List.of(pump("Насос", 10L, "return 1;"));
        List<ComponentCreateDto> mine = List.of(pump("Насос", 10L, "return 42;"));
        List<ComponentCreateDto> theirs = List.of(pump("Насос", 10L, "return 1;"));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.changes())
                .as("это моя правка, показывать её мне незачем")
                .isEmpty();
    }

    @Test
    void theirModificationIsReportedOnce() {
        List<ComponentCreateDto> base = List.of(pump("Насос", 10L, "return 1;"));
        List<ComponentCreateDto> mine = List.of(pump("Насос", 10L, "return 1;"));
        List<ComponentCreateDto> theirs = List.of(pump("Насос", 10L, "return 55;"));

        SceneMerge result = merger.merge(base, mine, theirs);

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.entity()).isEqualTo("script");
            assertThat(change.path()).isEqualTo("Насос / Открыть");
            assertThat(change.change()).isEqualTo(ChangeKind.MODIFIED);
        });
    }

    @Test
    void theirAdditionIsReportedAsAdded() {
        ComponentCreateDto theirsPump = pump("Насос", 10L, "return 1;");
        ScriptCreateDto extra = new ScriptCreateDto();
        extra.setId(11L);
        extra.setName("Закрыть");
        extra.setScript("return 2;");
        theirsPump.getScripts().add(extra);

        SceneMerge result = merger.merge(
                List.of(pump("Насос", 10L, "return 1;")),
                List.of(pump("Насос", 10L, "return 1;")),
                List.of(theirsPump));

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.change()).isEqualTo(ChangeKind.ADDED);
            assertThat(change.path()).isEqualTo("Насос / Закрыть");
        });
    }

    @Test
    void conflictProducesNoChangeEntry() {
        SceneMerge result = merger.merge(
                List.of(pump("Насос", 10L, "return 1;")),
                List.of(pump("Насос", 10L, "return 42;")),
                List.of(pump("Насос", 10L, "return 55;")));

        assertThat(result.isClean()).isFalse();
        assertThat(result.changes())
                .as("конфликт отменяет сохранение целиком — отчитываться не о чем")
                .isEmpty();
    }
}
