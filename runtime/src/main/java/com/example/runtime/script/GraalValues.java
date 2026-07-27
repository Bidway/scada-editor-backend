package com.example.runtime.script;

import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Приведение GraalVM {@link Value} к контекстонезависимому Java-значению.
 * <p>
 * Критично, что результат не держит ссылок на контекст: сразу после выполнения скрипта
 * контекст возвращается в пул ({@code release}) и переиспользуется другим потоком, а
 * сконвертированное значение к этому моменту уже лежит в {@code props}/{@code propertyValues}
 * и позже сериализуется Jackson'ом в WS-фрейм. {@code value.as(Object.class)} для JS-объекта
 * или массива вернул бы прокси, привязанный к живому контексту, — отсюда «Context is closed»
 * либо гонка. Здесь всё материализуется в обычные Java-типы (примитивы / List / Map).
 */
final class GraalValues {

    private GraalValues() {
    }

    static Object toJava(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.fitsInLong() ? value.asLong() : value.asDouble();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            long size = value.getArraySize();
            List<Object> list = new ArrayList<>((int) Math.min(size, 1024));
            for (long i = 0; i < size; i++) {
                list.add(toJava(value.getArrayElement(i)));
            }
            return list;
        }
        // Обычный JS-объект: копируем по ключам. Функции (canExecute) в props не переносим.
        if (value.hasMembers() && !value.canExecute()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, toJava(value.getMember(key)));
            }
            return map;
        }
        return value.toString();
    }
}
