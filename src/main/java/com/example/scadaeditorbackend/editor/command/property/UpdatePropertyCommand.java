package com.example.scadaeditorbackend.editor.command.property;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.editor.model.Component;
import com.example.scadaeditorbackend.editor.repository.ComponentPropertyRepository;
import lombok.RequiredArgsConstructor;
import com.example.scadaeditorbackend.editor.model.ComponentProperty;

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
