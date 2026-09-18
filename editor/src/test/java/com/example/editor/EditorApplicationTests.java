package com.example.editor;

import com.example.editor.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Контекст поднимается на Testcontainers и профиле test, как остальные IT. Голый
 * {@code @SpringBootTest} брал main-овский application.yml — рабочую dev-базу savushkin с
 * ddl-auto: update — и правил её схему при каждом прогоне (scada-791).
 */
@SpringBootTest
@ActiveProfiles("test")
class EditorApplicationTests extends PostgresTestContainerSupport {

    @Test
    void contextLoads() {
    }

}
