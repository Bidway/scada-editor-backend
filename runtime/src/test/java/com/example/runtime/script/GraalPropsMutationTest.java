package com.example.runtime.script;

import com.example.runtime.config.RuntimeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scada-4yy: точечная запись в уже прочитанное свойство. {@code props.arr[0] = 99} и
 * {@code props.obj.y = 'x'} обязаны доходить до исходной коллекции, а не в её копию.
 * <p>
 * Это не косметика: скрипт, который правит поле и потом пишет тег в ПЛК на его основе, при
 * потере записи отработает на старом значении молча — исключения нет, в логе пусто.
 * <p>
 * Тесты идут двумя запусками намеренно: внутри одного {@code eval} значение ещё живёт как
 * обычный JS-объект и до {@link MapProxyObject} не доходит вовсе.
 */
class GraalPropsMutationTest {

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
    @DisplayName("props.arr[0] = 99 доезжает до исходного списка")
    void arrayElementAssignment_reachesProps() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("arr", new ArrayList<>(List.of(1, 2, 3)));

        engine.runAction("props.arr[0] = 99;", props, TagWriteSink.NOOP);

        List<?> arr = (List<?>) props.get("arr");
        assertThat(((Number) arr.get(0)).intValue()).isEqualTo(99);
        assertThat(arr).hasSize(3);
    }

    @Test
    @DisplayName("props.obj.y = 'x' доезжает до исходной карты")
    void nestedFieldAssignment_reachesProps() {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("x", 5);
        props.put("obj", nested);

        engine.runAction("props.obj.y = 'hi';", props, TagWriteSink.NOOP);

        assertThat(nested).containsEntry("y", "hi");
        assertThat(props.get("obj"))
                .as("props отдаёт ту же карту, а не копию")
                .isSameAs(nested);
    }

    @Test
    @DisplayName("вложенная запись через два уровня доезжает так же")
    void deepAssignment_reachesProps() {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("value", 1);
        List<Object> rows = new ArrayList<>();
        rows.add(inner);
        props.put("rows", rows);

        engine.runAction("props.rows[0].value = 42;", props, TagWriteSink.NOOP);

        assertThat(((Number) inner.get("value")).intValue()).isEqualTo(42);
    }

    /**
     * Обёртка кешируется по identity исходной коллекции. Без кеша каждое обращение строило бы
     * новую — отсюда и ложное {@code props.arr !== props.arr}, и квадрат в цикле по индексу.
     */
    @Test
    @DisplayName("props.arr, прочитанный дважды, — один и тот же объект")
    void sameCollectionReadTwice_isSameObject() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("arr", new ArrayList<>(List.of(1, 2, 3)));

        engine.runAction("props.same = (props.arr === props.arr);", props, TagWriteSink.NOOP);

        assertThat(props.get("same")).isEqualTo(true);
    }

    /** Перезапись всего свойства целиком не должна оставлять скрипту старую обёртку. */
    @Test
    @DisplayName("после props.arr = [...] запись по индексу правит новый список")
    void replacingWholeProperty_thenWritingByIndex() {
        Map<String, Object> props = new LinkedHashMap<>();
        List<Object> original = new ArrayList<>(List.of(1, 2, 3));
        props.put("arr", original);

        engine.runAction("props.arr = [7, 8]; props.arr[0] = 99;", props, TagWriteSink.NOOP);

        List<?> arr = (List<?>) props.get("arr");
        assertThat(arr).hasSize(2);
        assertThat(((Number) arr.get(0)).intValue()).isEqualTo(99);
        assertThat(original)
                .as("прежний список отвязан от props и меняться не должен")
                .containsExactly(1, 2, 3);
    }
}
