package com.example.runtime.recipe;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProcedureExecutionTest {

    @Test
    void pause_freezesElapsed_andResumeExcludesPausedTime() throws InterruptedException {
        ProcedureExecution execution = ProcedureExecution.restored("r", 0,
                Instant.now().minusSeconds(10), false, false, null, null);

        execution.pause("тест");
        long atPause = execution.elapsedMs();
        Thread.sleep(80);

        assertThat(execution.paused()).isTrue();
        assertThat(execution.elapsedMs()).isEqualTo(atPause);

        execution.resume();

        assertThat(execution.paused()).isFalse();
        assertThat(execution.pauseReason()).isNull();
        assertThat(execution.elapsedMs()).isBetween(atPause, atPause + 60);
    }
}
