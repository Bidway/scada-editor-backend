package com.example.scriptcore;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;

import java.util.List;
import java.util.function.Function;

/**
 * Массив данных проекта для JS. В отличие от {@link ListProxyArray}, запись не доходит до
 * исходника, а падает: снимок общий для всех тактов и сессий.
 */
final class ReadOnlyListProxy implements ProxyArray {

    private final List<?> list;
    private final Function<Object, Object> wrap;

    ReadOnlyListProxy(List<?> list, Function<Object, Object> wrap) {
        this.list = list;
        this.wrap = wrap;
    }

    @Override
    public Object get(long index) {
        if (index < 0 || index >= list.size()) {
            throw new ArrayIndexOutOfBoundsException("Array index out of bounds: " + index);
        }
        return wrap.apply(list.get((int) index));
    }

    @Override
    public void set(long index, Value value) {
        throw ReadOnlyValues.readOnly();
    }

    @Override
    public boolean remove(long index) {
        throw ReadOnlyValues.readOnly();
    }

    @Override
    public long getSize() {
        return list.size();
    }
}
