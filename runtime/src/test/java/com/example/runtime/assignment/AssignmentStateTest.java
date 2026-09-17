package com.example.runtime.assignment;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Команда уходит в командный топик того топика, чей префикс покрывает путь. Нет совпадения — не уходит. */
class AssignmentStateTest {

    @Test
    void командный_топик_выбирается_по_префиксу_пути_с_границей_сегмента() {
        AssignmentState state = new AssignmentState();
        state.update(List.of(
                new AssignmentState.TopicAssignment("scada.tags.site1", "scada-commands.site1",
                        "scada-command-results.site1", List.of("Барановичи-1")),
                new AssignmentState.TopicAssignment("scada.tags.site10", "scada-commands.site10",
                        "scada-command-results.site10", List.of("Барановичи-10"))),
                Set.of(8501L));

        assertThat(state.commandsTopicFor("Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST")).contains("scada-commands.site1");
        assertThat(state.commandsTopicFor("Барановичи-10.PLC.X")).contains("scada-commands.site10");
        assertThat(state.commandsTopicFor("Минск-1.PLC.X")).isEmpty();
        assertThat(state.isAssigned(8501L)).isTrue();
        assertThat(state.isAssigned(7L)).isFalse();
    }
}
