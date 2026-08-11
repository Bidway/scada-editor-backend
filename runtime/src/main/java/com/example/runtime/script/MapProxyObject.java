package com.example.runtime.script;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Делает Java Map<String,Object> видимой в JS как обычный мутируемый объект
 * (props.state = "on" и т.п.), без экспонирования произвольного доступа к Java.
 */
public final class MapProxyObject implements ProxyObject {

    private final Map<String, Object> map;

    public MapProxyObject(Map<String, Object> map) {
        this.map = map;
    }

    @Override
    public Object getMember(String key) {
        return wrap(map.get(key));
    }

    /**
     * {@link GraalValues#toJava} материализует JS-массив/объект в голый {@link List}/{@link Map}
     * (см. комментарий там же — контекстонезависимость), а такое значение, отданное как есть,
     * GraalVM видит host-объектом. Контекст построен с {@code HostAccess.NONE}
     * (см. {@code ScriptEngineService#newContext}), поэтому обращение к элементам/полям
     * host-объекта запрещено политикой — скрипт получает {@code undefined} без ошибки
     * (scada-2or). Оборачиваем в {@link ProxyArray}/{@link MapProxyObject}, чтобы JS видел
     * настоящий массив/объект. Списки оборачиваются рекурсивно на месте, потому что
     * {@link ProxyArray#fromList} не оборачивает элементы при чтении сам; вложенные карты
     * разворачиваются лениво — этим же методом при следующем {@code getMember}.
     * <p>
     * Внимание: на каждый вызов строится новая копия ({@code new ArrayList<>}/
     * {@code new LinkedHashMap<>}), а не живой вид поверх исходной коллекции. Запись по
     * индексу/полю в уже прочитанное значение ({@code props.arr[0] = 1}, {@code props.obj.x = 1})
     * уходит в копию и теряется — исходный {@code map} не меняется. Разобрано и заведено
     * отдельно, см. scada-4yy; здесь не чиним осознанно.
     */
    private static Object wrap(Object value) {
        if (value instanceof List<?> list) {
            List<Object> wrapped = new ArrayList<>(list.size());
            for (Object item : list) {
                wrapped.add(wrap(item));
            }
            return ProxyArray.fromList(wrapped);
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                typed.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return new MapProxyObject(typed);
        }
        return value;
    }

    @Override
    public Object getMemberKeys() {
        return map.keySet().toArray(new String[0]);
    }

    @Override
    public boolean hasMember(String key) {
        return map.containsKey(key);
    }

    @Override
    public void putMember(String key, Value value) {
        // Приводим к контекстонезависимому Java-значению здесь же: контекст живёт только
        // во время eval, а результат переживает его в props/propertyValues (см. GraalValues).
        map.put(key, GraalValues.toJava(value));
    }
}
