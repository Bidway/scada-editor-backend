package com.example.scadaeditorbackend.editor.command.property;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.editor.model.ComponentProperty;
import com.example.scadaeditorbackend.editor.repository.ComponentPropertyRepository;
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
