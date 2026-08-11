package com.example.channel.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Контейнер PostgreSQL для интеграционных тестов channel.
 * <p>
 * Контейнер поднимается статическим инициализатором, а не аннотациями {@code @Testcontainers}
 * и {@code @Container}: те отдают его жизненный цикл расширению JUnit, которое гасит
 * контейнер после КАЖДОГО тест-класса. С одним наследником это незаметно, а как только их
 * становится несколько — второй и дальше получают Connection refused на уже остановленный
 * контейнер. В editor на этом потеряли прогон целиком (scada-90m); здесь сразу делаем верно.
 * <p>
 * Останавливает контейнер хук завершения JVM. Обычно это делает Ryuk, но в этом окружении
 * он выключен (TESTCONTAINERS_RYUK_DISABLED в channel/build.gradle.kts), и без хука
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
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "channel");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.create_namespaces", () -> "true");
    }
}
