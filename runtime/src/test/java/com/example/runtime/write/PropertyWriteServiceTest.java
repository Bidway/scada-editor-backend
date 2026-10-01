package com.example.runtime.write;

import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.client.dto.EditorPropertyDto;
import com.example.runtime.dto.PropertyWriteItem;
import com.example.runtime.dto.PropertyWriteResult;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.TagSubscriptionIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Инспектор объектов: оператор задаёт локальное свойство, и это видят все мониторы проекта.
 * Свойство с тегом и нечисловое значение числового свойства отклоняются без записи.
 */
class PropertyWriteServiceTest {

    @Test
    void локальное_свойство_пишется_и_уходит_всем_мониторам_а_теговое_и_кривое_отклоняются() {
        EditorComponentDto component = new EditorComponentDto();
        component.setId(1L);
        component.setName("TANK1LT1");
        component.setProperties(List.of(
                property(10L, "P_MAX", "number", null),
                property(11L, "CLEVEL", "number", "Барановичи-1.BN1_MCA1.TANK1LT1.CLEVEL")));
        ProjectRuntime project = new ProjectRuntime(7L, component, TagSubscriptionIndex.build(component, 7L), null);
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        store.put(project);
        RuntimeSession writer = new RuntimeSession("a.1", project);
        RuntimeSession other = new RuntimeSession("a.2", project);
        project.addObserver(writer);
        project.addObserver(other);

        List<PropertyWriteResult> results = new PropertyWriteService(store).write(7L, List.of(
                item(10L, "12.5"), item(11L, "3"), item(10L, "много"), item(99L, "1")));

        assertThat(results).extracting(PropertyWriteResult::status).containsExactly(
                PropertyWriteResult.OK, PropertyWriteResult.TAG_PROPERTY,
                PropertyWriteResult.INVALID_VALUE, PropertyWriteResult.UNKNOWN_PROPERTY);
        assertThat(project.getPropertyValues()).containsEntry(10L, 12.5).doesNotContainKey(11L);
        for (RuntimeSession session : List.of(writer, other)) {
            assertThat(session.getOutboundBuffer().drainAll().properties())
                    .singleElement().satisfies(u -> assertThat(u.value()).isEqualTo(12.5));
        }
    }

    private static EditorPropertyDto property(Long id, String name, String valueType, String tagId) {
        EditorPropertyDto property = new EditorPropertyDto();
        property.setId(id);
        property.setName(name);
        property.setValue_type(valueType);
        property.setTag_id(tagId);
        return property;
    }

    private static PropertyWriteItem item(Long propertyId, String value) {
        PropertyWriteItem item = new PropertyWriteItem();
        item.setPropertyId(propertyId);
        item.setValue(value);
        return item;
    }
}
