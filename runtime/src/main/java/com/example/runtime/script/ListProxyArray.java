package com.example.runtime.script;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;

import java.util.List;

/**
 * Живой вид на Java-{@link List} для JS: чтение отдаёт обёрнутый элемент, запись по индексу
 * доходит до исходного списка.
 * <p>
 * Раньше здесь стоял {@code ProxyArray.fromList(new ArrayList<>(...))} — массив-копия, и
 * {@code props.arr[0] = 99} правил копию (scada-4yy). Пишет скрипт обычно затем, чтобы
 * следующим шагом отправить значение в ПЛК, поэтому потеря записи молчаливая и дорогая.
 */
final class ListProxyArray implements ProxyArray {

    private final List<Object> list;
    private final ProxyWrappers wrappers;

    ListProxyArray(List<Object> list, ProxyWrappers wrappers) {
        this.list = list;
        this.wrappers = wrappers;
    }

    @Override
    public Object get(long index) {
        checkBounds(index);
        return wrappers.wrap(list.get((int) index));
    }

    /**
     * Запись за концом списка — не ошибка: в JS {@code arr[arr.length] = x} это обычное
     * добавление. Дыру между концом и индексом заполняем null, как разрежённый JS-массив.
     */
    @Override
    public void set(long index, Value value) {
        if (index < 0 || index > Integer.MAX_VALUE) {
            throw new ArrayIndexOutOfBoundsException("Array index out of bounds: " + index);
        }
        Object converted = GraalValues.toJava(value);
        int i = (int) index;
        if (i < list.size()) {
            wrappers.forget(list.set(i, converted));
            return;
        }
        while (list.size() < i) {
            list.add(null);
        }
        list.add(converted);
    }

    @Override
    public long getSize() {
        return list.size();
    }

    private void checkBounds(long index) {
        if (index < 0 || index >= list.size()) {
            throw new ArrayIndexOutOfBoundsException("Array index out of bounds: " + index);
        }
    }
}
