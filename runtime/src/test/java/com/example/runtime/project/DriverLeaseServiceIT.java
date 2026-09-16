package com.example.runtime.project;

import com.example.runtime.persistence.DriverLeaseEntity;
import com.example.runtime.persistence.DriverLeaseRepository;
import com.example.runtime.persistence.DriverLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Риск, который закрывает этот тест: два экземпляра runtime, поднявшие один драйвер, начали бы
 * двигать одну мойку вдвоём и дважды писать в ПЛК.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DriverLeaseService.class)
@Testcontainers
class DriverLeaseServiceIT {

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
    DriverLeaseService leases;

    @Autowired
    DriverLeaseRepository repository;

    @Test
    void чужой_драйвер_не_отдаётся() {
        assertThat(leases.tryAcquire("Барановичи-1")).isTrue();

        DriverLeaseEntity taken = repository.findByDriver("Барановичи-1").orElseThrow();
        taken.setInstanceId("другой-экземпляр");
        repository.saveAndFlush(taken);

        assertThat(leases.tryAcquire("Барановичи-1")).isFalse();
    }
}
