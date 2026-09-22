package com.example.channel.importer;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Вставка и удаление базы каналов мимо CommandManager — принятое исключение из Command Pattern
 * для импорта .cdbx: импорт создаёт новую базу, 27 000 строк журнала засорили бы историю
 * отмены, атомарность даёт транзакция вызывающего сервиса.
 * <p>
 * JdbcTemplate, а не saveAll: у node.id генерация identity, при ней Hibernate пакетную вставку
 * отключает. Параметр ссылается на узел путём, поэтому узлы и параметры вставляются независимо.
 */
@Component
@RequiredArgsConstructor
public class CdbxImportWriter {

    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;

    public void write(List<String> nodes, List<PlannedParam> params) {
        jdbc.batchUpdate("INSERT INTO channel.node (id_node) VALUES (?)", nodes, BATCH,
                (statement, idNode) -> statement.setString(1, idNode));
        jdbc.batchUpdate("INSERT INTO channel.param (id_node, id_type, value) VALUES (?, ?, ?)", params, BATCH,
                (statement, param) -> {
                    statement.setString(1, param.idNode());
                    statement.setLong(2, param.idType());
                    statement.setString(3, param.value());
                });
    }

    /** Узел root, всё поддерево и их параметры. @return сколько узлов удалено */
    public int deleteTree(String root) {
        // «_» и «%» в имени проекта (BN1_MCA2) — подстановочные символы LIKE, их экранируем.
        String subtree = root.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%";
        jdbc.update("DELETE FROM channel.param WHERE id_node = ? OR id_node LIKE ? ESCAPE '\\'", root, subtree);
        return jdbc.update("DELETE FROM channel.node WHERE id_node = ? OR id_node LIKE ? ESCAPE '\\'", root, subtree);
    }
}
