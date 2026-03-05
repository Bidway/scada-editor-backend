package com.example.editor.command.property;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.model.ComponentProperty;
import com.example.editor.repository.ComponentPropertyRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpdatePropertyCommand implements Command<ComponentProperty> {

    private final ComponentPropertyRepository repository;
    private final Long id;
    private final ComponentProperty updatedData;

    @Override
    public CommandResult<ComponentProperty> execute() {

        ComponentProperty property = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Property not found"));

//        property.setName(updatedData.getName());
//        property.setValue(updatedData.getValue());

        return null;
    }
}
