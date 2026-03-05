package com.example.channel.config.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

@Entity
@Table(name = "command_log")
@Data
public class CommandLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String entityType;
    private Long entityId;
    private String commandType;


    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode payload;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode undoPayload;

    private LocalDateTime createdAt = LocalDateTime.now();

    public static CommandLog from(CommandResult r) {
        CommandLog log = new CommandLog();
        log.userId = r.getUserId();
        log.entityType = r.getEntityType();
        log.entityId = r.getEntityId();
        log.commandType = r.getCommandType();
        log.payload = r.getPayload();
        log.undoPayload = r.getUndoPayload();
        return log;
    }

    public CommandResult toResult() {
        return new CommandResult(
                userId, entityType, entityId, commandType, payload, undoPayload, null
        );
    }
}

