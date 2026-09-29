package com.example.runtime.project;

import com.example.runtime.session.TagSubscriptionIndex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Скрипт, начатый на старом выпуске, может закончиться после reload и записать свойство, которого
 * в новом выпуске нет. Такая запись отбрасывается: иначе в памяти и в property_value осталось бы
 * сиротское значение, а мониторам ушёл бы UPDATE несуществующего свойства.
 */
class ProjectRuntimeTest {

    @Test
    void запись_свойства_которого_нет_в_текущем_выпуске_отбрасывается() {
        TagSubscriptionIndex v1 = mock(TagSubscriptionIndex.class);
        when(v1.propertyName(11L)).thenReturn("old");
        ProjectRuntime project = new ProjectRuntime(8501L, new ProjectModel(1, null, null, v1), null);
        List<Long> persisted = new ArrayList<>();
        project.setPropertyValueSink((id, value) -> persisted.add(id));
        project.replaceModel(new ProjectModel(2, null, null, mock(TagSubscriptionIndex.class)));

        assertThat(project.putPropertyValue(11L, "поздно")).isFalse();

        assertThat(project.getPropertyValues()).doesNotContainKey(11L);
        assertThat(persisted).isEmpty();
    }
}
