package com.example.runtime.script;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.Map;

/**
 * Делает Java Map&lt;String,Object&gt; видимой в JS как обычный мутируемый объект
 * (props.state = "on" и т.п.), без экспонирования произвольного доступа к Java.
 */
public final class MapProxyObject implements ProxyObject {

    private final Map<String, Object> map;
    private final ProxyWrappers wrappers;

    public MapProxyObject(Map<String, Object> map) {
        this(map, new ProxyWrappers());
    }

    MapProxyObject(Map<String, Object> map, ProxyWrappers wrappers) {
        this.map = map;
        this.wrappers = wrappers;
    }

    /**
     * {@link GraalValues#toJava} материализует JS-массив/объект в голый {@code List}/{@code Map}
     * (см. комментарий там же — контекстонезависимость), а такое значение, отданное как есть,
     * GraalVM видит host-объектом. Контекст построен с {@code HostAccess.NONE}
     * (см. {@code ScriptEngineService#newContext}), поэтому обращение к элементам/полям
     * host-объекта запрещено политикой — скрипт получает {@code undefined} без ошибки
     * (scada-2or). Поэтому коллекции оборачиваются в {@link ListProxyArray}/
     * {@link MapProxyObject} — живые виды поверх исходных, а не копии, иначе точечная запись
     * теряется (scada-4yy). Обёртки кеширует {@link ProxyWrappers}.
     */
    @Override
    public Object getMember(String key) {
        return wrappers.wrap(map.get(key));
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
        wrappers.forget(map.put(key, GraalValues.toJava(value)));
    }
}
