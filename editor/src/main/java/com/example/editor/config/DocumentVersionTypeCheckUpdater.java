package com.example.editor.config;

import com.example.editor.model.version.DocumentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Приводит CHECK-ограничение {@code document_version.target_type} к текущему {@link DocumentType}.
 * <p>
 * Hibernate создаёт это ограничение вместе с таблицей и при {@code ddl-auto: update} больше его не
 * трогает. В базе, где таблица появилась до {@code AUTOMATION}, ограничение пускало только
 * {@code SCENE}/{@code TEMPLATE}, и первое же сохранение автоматизации падало 500-й.
 * Миграций в проекте нет, поэтому ограничение пересобирается при старте из значений enum.
 */
@Component
@Slf4j
public class DocumentVersionTypeCheckUpdater {

    private static final String CONSTRAINT = "document_version_target_type_check";

    private final JdbcTemplate jdbcTemplate;
    private final String schema;

    public DocumentVersionTypeCheckUpdater(JdbcTemplate jdbcTemplate,
                                           @Value("${spring.jpa.properties.hibernate.default_schema:editor}") String schema) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void update() {
        String allowed = Arrays.stream(DocumentType.values())
                .map(type -> "'" + type.name() + "'")
                .collect(Collectors.joining(", "));
        String table = schema + ".document_version";
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + CONSTRAINT);
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + CONSTRAINT
                    + " CHECK (target_type IN (" + allowed + "))");
        } catch (DataAccessException e) {
            // Не валим старт: без ограничения сервис работает, а причина будет видна в логе.
            log.warn("Не удалось обновить {} на {}: {}", CONSTRAINT, table, e.getMessage());
        }
    }
}
