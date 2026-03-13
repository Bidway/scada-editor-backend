package com.example.channel.command;

import com.example.channel.config.command.Command;
import com.example.channel.config.command.CommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Map;
import java.util.function.Function;

public class CrudCommand<T> implements Command<T> {

    public enum Action { CREATE, UPDATE, DELETE }

    private final Long userId;
    private final Action action;
    private final JpaRepository<T, Long> repository;
    private final ObjectMapper mapper;

    private final T entity;
    private final T entityBeforeUpdate;

    private final Function<T, Long> idExtractor;

    public CrudCommand(
            Long userId,
            Action action,
            JpaRepository<T, Long> repository,
            ObjectMapper mapper,
            T entity,
            T entityBeforeUpdate,
            Function<T, Long> idExtractor
    ) {
        this.userId = userId;
        this.action = action;
        this.repository = repository;
        this.mapper = mapper;
        this.entity = entity;
        this.entityBeforeUpdate = entityBeforeUpdate;
        this.idExtractor = idExtractor;
    }

    @Override
    public CommandResult<T> execute() {

        T savedEntity = switch (action) {
            case CREATE -> repository.save(entity);
            case UPDATE -> repository.save(entity);
            case DELETE -> {
                repository.delete(entity);
                yield entity;
            }
        };

        Long entityId = idExtractor.apply(savedEntity);

        JsonNode undoPayload = switch (action) {

            case CREATE ->
                    mapper.valueToTree(Map.of("id", entityId));

            case DELETE ->
                    mapper.valueToTree(savedEntity);

            case UPDATE ->
                    mapper.valueToTree(entityBeforeUpdate);
        };

        return new CommandResult<>(
                userId,
                entity.getClass().getSimpleName(),
                entityId,
                action.name(),
                mapper.valueToTree(savedEntity),
                undoPayload,
                savedEntity
        );
    }
}