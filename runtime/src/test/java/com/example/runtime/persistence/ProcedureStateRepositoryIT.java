package com.example.runtime.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ProcedureStateRepositoryIT {

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
    ProcedureStateRepository repository;

    @Test
    void сохраняет_и_читает_состояние_процедуры() throws Exception {
        ProcedureStateEntity state = new ProcedureStateEntity();
        state.setProjectId(8501L);
        state.setRecipeId("танк-сырого-молока-2-дезинфекция");
        state.setStepIndex(12);
        state.setStepEnteredAt(Instant.parse("2026-09-16T10:00:00Z"));
        state.setConfirmed(false);
        state.setAccumulatedActions(new ObjectMapper().readTree("[{\"tag\":\"V0\",\"value\":1}]"));
        state.setStartedBy("stand");
        state.setStartedAt(Instant.parse("2026-09-16T09:30:00Z"));
        repository.save(state);

        Optional<ProcedureStateEntity> found =
                repository.findByProjectIdAndRecipeId(8501L, "танк-сырого-молока-2-дезинфекция");

        assertThat(found).isPresent();
        assertThat(found.get().getStepIndex()).isEqualTo(12);
        assertThat(found.get().getAccumulatedActions().get(0).get("tag").asText()).isEqualTo("V0");
    }
}
