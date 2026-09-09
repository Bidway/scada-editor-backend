package com.example.runtime.script;

import com.example.runtime.config.RuntimeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptEngineConditionTest {

    private ScriptEngineService engine;

    @BeforeEach
    void setUp() {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getScript().setContextPoolSize(1);
        properties.getScript().setOnChangeThreads(1);
        engine = new ScriptEngineService(properties);
        engine.initPool();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    void emptyScript_isAlwaysTrue() {
        assertThat(engine.runCondition(null, 0, false, path -> null)).isTrue();
        assertThat(engine.runCondition("", 0, false, path -> null)).isTrue();
    }

    @Test
    void elapsedMsCondition_evaluatesAgainstPassedValue() {
        String script = "return elapsedMs >= 2000;";

        assertThat(engine.runCondition(script, 1000, false, path -> null)).isFalse();
        assertThat(engine.runCondition(script, 2500, false, path -> null)).isTrue();
    }

    @Test
    void confirmedCondition_evaluatesAgainstPassedValue() {
        String script = "return confirmed;";

        assertThat(engine.runCondition(script, 0, false, path -> null)).isFalse();
        assertThat(engine.runCondition(script, 0, true, path -> null)).isTrue();
    }

    @Test
    void readProjectTag_callsTagReaderWithGivenPath() {
        String script = "return readProjectTag('LINE1.LEVEL') >= 300;";

        assertThat(engine.runCondition(script, 0, false, path -> path.equals("LINE1.LEVEL") ? 124.0 : null))
                .isFalse();
        assertThat(engine.runCondition(script, 0, false, path -> path.equals("LINE1.LEVEL") ? 300.0 : null))
                .isTrue();
    }

    @Test
    void nonBooleanReturn_isTreatedAsFalse() {
        assertThat(engine.runCondition("return 42;", 0, false, path -> null)).isFalse();
    }

    @Test
    void writeTagCalledFromCondition_isSafeNoOp() {
        String script = "writeTag('x', true); return elapsedMs >= 0;";

        assertThat(engine.runCondition(script, 0, false, path -> null)).isTrue();
    }
}
