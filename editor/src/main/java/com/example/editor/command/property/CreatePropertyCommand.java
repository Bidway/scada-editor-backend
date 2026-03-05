package com.example.editor.command.property;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.model.ComponentProperty;
import com.example.editor.repository.ComponentPropertyRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CreatePropertyCommand implements Command<ComponentProperty> {

    private final ComponentPropertyRepository repository;
    private final ComponentProperty property;

    @Override
    public CommandResult<ComponentProperty> execute() {
        repository.save(property);
        return null;
    }
}
