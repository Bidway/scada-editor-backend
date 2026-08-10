package com.example.editor.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Контейнер PostgreSQL для интеграционных тестов editor. Копия того же класса из auth,
 * отличается схемой: у каждого сервиса в базе savushkin своя, и Hibernate создаёт её сам
 * благодаря create_namespaces.
 * <p>
 * Контейнер поднимается статическим инициализатором, а не аннотациями {@code @Testcontainers}
 * и {@code @Container}: те отдают его жизненный цикл расширению JUnit, которое гасит
 * контейнер после КАЖДОГО тест-класса. С одним наследником это незаметно (так в auth), а
 * здесь их шесть — второй и дальше получали Connection refused на уже остановленный
 * контейнер, и весь :editor:test был красный (scada-90m).
 * <p>
 * Останавливает контейнер хук завершения JVM. Обычно это делает Ryuk, но в этом окружении
 * он выключен (TESTCONTAINERS_RYUK_DISABLED в editor/build.gradle.kts), и без хука
 * контейнер утёк бы после каждого прогона.
 */
public abstract class PostgresTestContainerSupport {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    static {
        postgres.start();
        Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop));
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "editor");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.create_namespaces", () -> "true");
    }
}
