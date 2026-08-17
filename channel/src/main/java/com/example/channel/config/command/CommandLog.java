package com.example.channel.config.command;

import com.example.channel.command.CommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "command_log", schema = "channel")
@Data
public class CommandLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userName;
    private String entityType;
    private Long entityId;
    private String commandType;

    @Column(name = "batch_id")
    private UUID batchId;

    private Integer sequence;

    @Column(name = "undone_at")
    private LocalDateTime undoneAt;


    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode payload;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode undoPayload;

    private LocalDateTime createdAt = LocalDateTime.now();

    public static CommandLog from(CommandResult<?> r) {
        CommandLog log = new CommandLog();
        log.userName = r.getUserName();
        log.entityType = r.getEntityType();
        log.entityId = r.getEntityId();
        log.commandType = r.getCommandType();
        log.batchId = r.getBatchId();
        log.sequence = r.getSequence();
        log.payload = r.getPayload();
        log.undoPayload = r.getUndoPayload();
        return log;
    }
}

