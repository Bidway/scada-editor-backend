package com.example.editor.command.component;

import com.example.shared.command.Command;
import com.example.shared.command.CommandResult;
import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.model.component.Component;
import com.example.editor.repository.component.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class CreateComponentCommand implements Command<List<ComponentResponseDto>> {

    private final ComponentRepository repository;
    private final List<Component> preparedComponents;
    private final ComponentMapper componentMapper;
    private final ObjectMapper mapper;
    private final String userName;

    @Override
    public CommandResult<List<ComponentResponseDto>> execute() {
        List<Component> saved = repository.saveAll(preparedComponents);
        List<ComponentResponseDto> response = componentMapper.toDtoList(saved);

        List<Long> allIds = saved.stream()
                .flatMap(this::flatten)
                .map(Component::getId)
                .toList();

        JsonNode payload = mapper.valueToTree(Map.of("ids", allIds));
        JsonNode undoPayload = mapper.valueToTree(Map.of("ids", allIds));

        return new CommandResult<>(
                userName, "component", null, "CREATE_COMPONENT",
                payload, undoPayload, response
        );
    }

    private Stream<Component> flatten(Component component) {
        return Stream.concat(
                Stream.of(component),
                component.getChildren().stream().flatMap(this::flatten)
        );
    }
}
