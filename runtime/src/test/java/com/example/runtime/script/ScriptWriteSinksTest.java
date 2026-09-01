package com.example.runtime.script;

import com.example.runtime.config.RuntimeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Три функции записи, видимые скрипту, должны бить каждая в свой sink и не путаться
 * друг с другом: {@code writeTag} — по имени свойства текущего компонента,
 * {@code writeTagPath} — по абсолютному пути тега (любого проекта), {@code writeProjectTag} —
 * по короткому пути в рамках текущего проекта. Резолв путей ({@code TagSubscriptionIndex})
 * здесь не участвует — sink'и подставляются уже готовыми, движок лишь связывает имя JS-функции
 * с нужным из них.
 */
class ScriptWriteSinksTest {

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
    @DisplayName("writeTag('ST', true) уходит в sink свойств, а не в путевые")
    void writeTag_hitsPropertySink() {
        Recorder property = new Recorder();
        ScriptWriteSinks sinks = new ScriptWriteSinks(property, new Recorder(), new Recorder());

        engine.runAction("writeTag('ST', true);", new LinkedHashMap<>(), sinks);

        assertThat(property.name).isEqualTo("ST");
        assertThat(property.value).isEqualTo(true);
    }

    @Test
    @DisplayName("writeTagPath('Барановичи-1.BN1_MCA1.FQT_ST.LINE1FQT1.ST', 12) уходит в путевой sink")
    void writeTagPath_hitsPathSink() {
        Recorder path = new Recorder();
        ScriptWriteSinks sinks = new ScriptWriteSinks(new Recorder(), path, new Recorder());

        engine.runAction("writeTagPath('Барановичи-1.BN1_MCA1.FQT_ST.LINE1FQT1.ST', 12);",
                new LinkedHashMap<>(), sinks);

        assertThat(path.name).isEqualTo("Барановичи-1.BN1_MCA1.FQT_ST.LINE1FQT1.ST");
        assertThat(((Number) path.value).doubleValue()).isEqualTo(12.0);
    }

    @Test
    @DisplayName("writeProjectTag('FQT_ST.LINE1FQT1.ST', 5) уходит в sink короткого пути проекта")
    void writeProjectTag_hitsProjectPathSink() {
        Recorder projectTag = new Recorder();
        ScriptWriteSinks sinks = new ScriptWriteSinks(new Recorder(), new Recorder(), projectTag);

        engine.runAction("writeProjectTag('FQT_ST.LINE1FQT1.ST', 5);", new LinkedHashMap<>(), sinks);

        assertThat(projectTag.name).isEqualTo("FQT_ST.LINE1FQT1.ST");
        assertThat(((Number) projectTag.value).doubleValue()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("ScriptWriteSinks.NOOP игнорирует все три вызова без исключений")
    void noopSinks_swallowAllThreeCalls() {
        Map<String, Object> props = new LinkedHashMap<>();

        engine.runAction(
                "writeTag('a', 1); writeTagPath('b.c', 2); writeProjectTag('d.e', 3);",
                props, ScriptWriteSinks.NOOP);
        // Отсутствие исключения — и есть проверка: NOOP не должен ронять исполнение скрипта.
    }

    private static final class Recorder implements TagWriteSink {
        String name;
        Object value;

        @Override
        public void write(String name, Object value) {
            this.name = name;
            this.value = value;
        }
    }
}
