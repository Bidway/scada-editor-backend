package com.example.runtime.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionOutboundBufferTest {

    @Test
    void offerProcedureEvent_isDrainedAlongsideTagsAndProperties() {
        SessionOutboundBuffer buffer = new SessionOutboundBuffer();
        ProcedureEvent event = new ProcedureEvent("r1", 0, "Шаг 1",
                ProcedureEvent.Kind.STEP_STARTED, null);

        buffer.offerProcedureEvent(event);

        assertThat(buffer.isEmpty()).isFalse();
        SessionOutboundBuffer.Drained drained = buffer.drainAll();
        assertThat(drained.procedures()).containsExactly(event);
        assertThat(buffer.isEmpty()).isTrue();
    }
}
