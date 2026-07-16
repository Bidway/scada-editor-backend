package com.example.runtime.script;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;

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
        return map.get(key);
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
        map.put(key, value.isNull() ? null : value.as(Object.class));
    }
}
