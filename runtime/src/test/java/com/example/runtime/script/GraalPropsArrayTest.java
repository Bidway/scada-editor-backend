package com.example.runtime.script;

import com.example.runtime.config.RuntimeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия на scada-2or: значение-массив/объект, записанное в {@code props} одним
 * запуском скрипта, должно читаться обратно следующим запуском. Баг (если он есть)
 * проявляется только между двумя отдельными {@code eval}: внутри одного выполнения
 * значение ещё живёт как обычный JS-объект и не проходит через
 * {@link GraalValues#toJava} / {@link MapProxyObject#getMember}.
 */
class GraalPropsArrayTest {

    private RuntimeProperties properties;
    private ScriptEngineService engine;

    @BeforeEach
    void setUp() {
        properties = new RuntimeProperties();
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
    @DisplayName("props.arr[0], записанный одним запуском, читается следующим")
    void arrayPropertySurvivesBetweenRuns() {
        Map<String, Object> props = new LinkedHashMap<>();

        engine.runAction("props.arr = [1, 2, 3];", props, ScriptWriteSinks.NOOP);
        assertThat(props.get("arr")).isInstanceOf(java.util.List.class);

        engine.runAction("props.readBack = props.arr[0];", props, ScriptWriteSinks.NOOP);

        // Сравниваем по числовому значению, а не по классу: GraalValues.toJava в этой
        // сборке GraalJS отдаёт числа как Double независимо от того, целые они или нет
        // (не связано со scada-2or — заведено отдельно как scada-63q). Здесь важно, что
        // значение вообще дошло, а не осталось null/undefined.
        assertThat(props.get("readBack")).isInstanceOf(Number.class);
        assertThat(((Number) props.get("readBack")).doubleValue()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("props.obj.x, записанный одним запуском, читается следующим")
    void objectPropertySurvivesBetweenRuns() {
        Map<String, Object> props = new LinkedHashMap<>();

        engine.runAction("props.obj = { x: 5, y: 'hi' };", props, ScriptWriteSinks.NOOP);
        assertThat(props.get("obj")).isInstanceOf(Map.class);

        engine.runAction("props.readBack = props.obj.x;", props, ScriptWriteSinks.NOOP);

        assertThat(props.get("readBack")).isInstanceOf(Number.class);
        assertThat(((Number) props.get("readBack")).doubleValue()).isEqualTo(5.0);
    }
}
