package com.example.editor.command.property;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class DeletePropertyCommand implements Command<Void> {

    private final ComponentPropertyRepository repository;
    private final Long id;
    private final String userName;
    private final ObjectMapper mapper;

    @Override
    public CommandResult<Void> execute() {
        repository.deleteById(id);
        JsonNode payload = mapper.valueToTree(Map.of("id", id));
        return new CommandResult<>(
                userName,
                "component_property",
                id,
                "DELETE_PROPERTY",
                payload,
                payload,
                null
        );
    }
}
