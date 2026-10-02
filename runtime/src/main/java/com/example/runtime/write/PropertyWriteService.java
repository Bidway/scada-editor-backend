package com.example.runtime.write;

import com.example.runtime.dto.PropertyWriteItem;
import com.example.runtime.dto.PropertyWriteResult;
import com.example.runtime.kafka.ValueCoercion;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.runtime.recipe.ProjectNotInOperationException;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.TagSubscriptionIndex;
import com.example.runtime.stream.PropertyUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Запись локальных свойств оператором из инспектора объектов — в обход скриптов. Путь тот же,
 * что у скрипта: {@link ProjectRuntime#putPropertyValue} (память + сохранение в
 * {@code property_value}), затем UPDATE всем мониторам проекта.
 * <p>
 * Свойства с тегом сюда не пускаются: их значение приходит телеметрией и было бы затёрто
 * следующим же опросом — оператор увидел бы, что «записалось», а в ПЛК ничего не ушло.
 */
@Service
@RequiredArgsConstructor
public class PropertyWriteService {

    private final ProjectRuntimeStore projectStore;

    public List<PropertyWriteResult> write(Long projectId, List<PropertyWriteItem> writes) {
        ProjectRuntime project = projectStore.get(projectId);
        if (project == null) {
            throw new ProjectNotInOperationException(projectId);
        }
        // Индекс — один раз на запрос: reload может сменить выпуск посреди пачки.
        TagSubscriptionIndex index = project.getIndex();
        long ts = System.currentTimeMillis();
        List<PropertyWriteResult> results = new ArrayList<>();
        List<PropertyUpdate> changed = new ArrayList<>();
        for (PropertyWriteItem item : writes) {
            Long propertyId = item.getPropertyId();
            String name = index.propertyName(propertyId);
            if (name == null) {
                results.add(unknown(propertyId));
                continue;
            }
            if (index.tagIdOfProperty(propertyId) != null) {
                results.add(new PropertyWriteResult(propertyId, false, PropertyWriteResult.TAG_PROPERTY,
                        "Свойство «" + name + "» привязано к тегу — значение пишется в ПЛК"));
                continue;
            }
            Object value;
            try {
                value = coerce(item.getValue(), index.propertyValueType(propertyId));
            } catch (IllegalArgumentException e) {
                results.add(new PropertyWriteResult(propertyId, false, PropertyWriteResult.INVALID_VALUE,
                        "«" + name + "»: " + e.getMessage()));
                continue;
            }
            // Выпуск мог смениться после взятия индекса: тогда свойства уже нет.
            if (!project.putPropertyValue(propertyId, value)) {
                results.add(unknown(propertyId));
                continue;
            }
            changed.add(new PropertyUpdate(propertyId, name, value, ts));
            results.add(new PropertyWriteResult(propertyId, true, PropertyWriteResult.OK, null));
        }
        // Значения общие на проект: видят все мониторы, в том числе писавший — тем же UPDATE.
        for (RuntimeSession session : project.sessions()) {
            changed.forEach(session.getOutboundBuffer()::offerProperty);
        }
        return results;
    }

    private static PropertyWriteResult unknown(Long propertyId) {
        return new PropertyWriteResult(propertyId, false, PropertyWriteResult.UNKNOWN_PROPERTY,
                "Свойства " + propertyId + " нет в работающем выпуске проекта");
    }

    /**
     * Строже {@link ValueCoercion#coerce}: там нераспознанное число уходит строкой «на усмотрение
     * драйвера», а здесь драйвера нет — строка в числовом свойстве сломала бы арифметику скриптов.
     */
    static Object coerce(String value, String valueType) {
        Object coerced = ValueCoercion.coerce(value, valueType);
        boolean numeric = valueType != null && switch (valueType.trim().toLowerCase()) {
            case "number", "double", "float", "real", "int", "integer", "long" -> true;
            default -> false;
        };
        if (numeric && !(coerced instanceof Number)) {
            throw new IllegalArgumentException("значение «" + value + "» не является числом");
        }
        return coerced;
    }
}
