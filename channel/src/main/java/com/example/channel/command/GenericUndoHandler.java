package com.example.channel.command;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandResult;
import com.example.channel.config.command.UndoHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Set;

@Component
public class GenericUndoHandler implements UndoHandler {

    private final ObjectMapper mapper;
    private final ApplicationContext context;
    private final Repositories repositories;

    public GenericUndoHandler(ObjectMapper mapper, ApplicationContext context) {
        this.mapper = mapper;
        this.context = context;
        this.repositories = new Repositories(context);
    }


    @Override
    public boolean supports(String commandType) {
        // Поддержка всех CRUD action
        return Set.of("CREATE", "UPDATE", "DELETE").contains(commandType);
    }

    @Override
    public CommandResult undo(CommandLog log, String userName) {

        String action = log.getCommandType();
        String entityType = log.getEntityType();

        try {
            Class<?> clazz = Class.forName("com.example.channel.model." + entityType);

            Object undoEntity = mapper.convertValue(log.getUndoPayload(), clazz);


            var repoInfo = repositories.getRepositoryInformationFor(clazz)
                    .orElseThrow(() -> new RuntimeException(
                            "Repository not found for " + clazz.getName()
                    ));

            Class<?> repoInterface = repoInfo.getRepositoryInterface();

            JpaRepository repository =
                    (JpaRepository) context.getBean(repoInterface);



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
                    userName,
                    entityType,
                    log.getEntityId(),
                    action,
                    null,
                    null,
                    mapper.valueToTree(result),
                    log.getPayload(),
                    result
            );

        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
