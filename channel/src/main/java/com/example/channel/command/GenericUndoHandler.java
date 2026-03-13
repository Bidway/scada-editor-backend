package com.example.channel.command;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandResult;
import com.example.channel.config.command.UndoHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class GenericUndoHandler implements UndoHandler {

    private final ObjectMapper mapper;
    private final ApplicationContext context;

    public GenericUndoHandler(ObjectMapper mapper, ApplicationContext context) {
        this.mapper = mapper;
        this.context = context;
    }

    @Override
    public boolean supports(String commandType) {
        // Поддержка всех CRUD action
        return Set.of("CREATE", "UPDATE", "DELETE").contains(commandType);
    }

    @Override
    public CommandResult undo(CommandLog log, Long userId) {

        String action = log.getCommandType();
        String entityType = log.getEntityType();

        try {
            Class<?> clazz = Class.forName("com.example.channel.model." + entityType);

            Object undoEntity = mapper.convertValue(log.getUndoPayload(), clazz);

            String repositoryBeanName =
                    Character.toLowerCase(clazz.getSimpleName().charAt(0))
                            + clazz.getSimpleName().substring(1)
                            + "Repository";

            JpaRepository repository =
                    (JpaRepository) context.getBean(repositoryBeanName);

            Object result = switch (action) {
                case "CREATE" -> {
                    // undo create → delete
                    repository.delete(undoEntity);
                    yield null;
                }
                case "DELETE" -> {
                    // undo delete → restore entity
                    yield repository.save(undoEntity);
                }
                case "UPDATE" -> {
                    // undo update → восстановить old state
                    yield repository.save(undoEntity);
                }
                default -> throw new RuntimeException("Unknown action " + action);
            };

            return new CommandResult<>(
                    userId,
                    entityType,
                    log.getEntityId(),
                    action,
                    mapper.valueToTree(result),
                    log.getPayload(), // обратное undoPayload
                    result
            );

        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
