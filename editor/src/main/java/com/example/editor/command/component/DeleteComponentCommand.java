package com.example.editor.command.component;

import com.example.shared.command.Command;
import com.example.shared.command.CommandResult;
import com.example.editor.repository.component.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class DeleteComponentCommand implements Command<Void> {

    private final ComponentRepository repository;
    private final List<Long> ids;
    private final String userName;
    private final ObjectMapper mapper;

    @Override
    public CommandResult<Void> execute() {
        ids.forEach(repository::deleteById);
        JsonNode idsPayload = mapper.valueToTree(Map.of("ids", ids));
        return new CommandResult<>(
                userName,
                "component",
                null,
                "DELETE_COMPONENT",
                idsPayload,
                idsPayload,
                null
        );
    }
}
