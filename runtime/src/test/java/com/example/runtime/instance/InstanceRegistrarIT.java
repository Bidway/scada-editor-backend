package com.example.runtime.instance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Два экземпляра с одним именем читали бы одни топики и писали бы одни назначения — второй
 * обязан отказаться стартовать, пока первый жив. Перезапуск того же экземпляра — не дубль.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InstanceRegistrarIT {

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
    InstanceRepository repository;

    @Test
    void живой_дубль_имени_не_стартует_а_перезапуск_на_том_же_адресе_проходит() {
        InstanceEntity alive = new InstanceEntity();
        alive.setInstanceId("runtime-1");
        alive.setBaseUrl("http://10.0.0.11:8085");
        alive.setLastSeenAt(Instant.now());
        repository.saveAndFlush(alive);

        InstanceRegistrar sameAddress = new InstanceRegistrar(
                new InstanceIdentity("runtime-1", "http://10.0.0.11:8085"), repository);
        InstanceRegistrar otherAddress = new InstanceRegistrar(
                new InstanceIdentity("runtime-1", "http://10.0.0.12:8085"), repository);

        assertThatCode(sameAddress::register).doesNotThrowAnyException();
        assertThatThrownBy(otherAddress::register)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runtime-1")
                .hasMessageContaining("http://10.0.0.11:8085");
    }
}
