package com.example.runtime.assignment;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.client.dto.EditorPropertyDto;
import com.example.runtime.instance.InstanceEntity;
import com.example.runtime.instance.InstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Главное правило этапа: один топик и один проект — один экземпляр. Первичный ключ ловит равные
 * топики, сервис — пересекающиеся префиксы и проект, чьи теги не покрыты топиками экземпляра.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AssignmentService.class)
@Testcontainers
class AssignmentServiceIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("runtime-schema.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    AssignmentService service;

    @Autowired
    InstanceRepository instances;

    @MockBean
    EditorClient editorClient;

    @BeforeEach
    void instancesExist() {
        for (String id : List.of("runtime-1", "runtime-2")) {
            InstanceEntity row = new InstanceEntity();
            row.setInstanceId(id);
            row.setBaseUrl("http://" + id + ":8085");
            row.setLastSeenAt(Instant.now());
            instances.saveAndFlush(row);
        }
    }

    @Test
    void топик_и_пересекающийся_префикс_не_достаются_второму_экземпляру() {
        service.assignTopic("runtime-1", "scada.tags.site1", "scada-commands.site1",
                "scada-command-results.site1", List.of("Барановичи-1.BN1_MCA1"), "admin");

        assertThatThrownBy(() -> service.assignTopic("runtime-2", "scada.tags.site1", "scada-commands.x",
                "scada-command-results.x", List.of("Минск-1"), "admin"))
                .isInstanceOf(AssignmentConflictException.class).hasMessageContaining("runtime-1");
        assertThatThrownBy(() -> service.assignTopic("runtime-2", "scada.tags.site2", "scada-commands.site2",
                "scada-command-results.site2", List.of("Барановичи-1.BN1_MCA1.V_ST_1"), "admin"))
                .isInstanceOf(AssignmentConflictException.class).hasMessageContaining("Барановичи-1.BN1_MCA1");
    }

    @Test
    void проект_с_непокрытым_путём_не_назначается_и_ответ_называет_пути() {
        service.assignTopic("runtime-1", "scada.tags.site1", "scada-commands.site1",
                "scada-command-results.site1", List.of("Барановичи-1.BN1_MCA1"), "admin");
        when(editorClient.getProjectTree(8501L)).thenReturn(projectWithTags(
                "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST", "Минск-1.PLC.X"));

        assertThatThrownBy(() -> service.assignProject("runtime-1", 8501L, "admin"))
                .isInstanceOfSatisfying(AssignmentConflictException.class,
                        e -> assertThat(e.uncoveredPaths()).containsExactly("Минск-1.PLC.X"));
    }

    private static EditorComponentDto projectWithTags(String... paths) {
        EditorComponentDto project = new EditorComponentDto();
        project.setId(8501L);
        project.setType("project");
        EditorComponentDto component = new EditorComponentDto();
        component.setId(1L);
        component.setType("button");
        List<EditorPropertyDto> properties = new java.util.ArrayList<>();
        long id = 1;
        for (String path : paths) {
            EditorPropertyDto property = new EditorPropertyDto();
            property.setId(id++);
            property.setName("p" + id);
            property.setTag_id(path);
            properties.add(property);
        }
        component.setProperties(properties);
        project.setChildren(List.of(component));
        return project;
    }
}
