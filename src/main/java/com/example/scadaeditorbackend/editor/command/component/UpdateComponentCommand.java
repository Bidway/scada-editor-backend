package com.example.scadaeditorbackend.editor.command.component;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.editor.model.Component;
import com.example.scadaeditorbackend.editor.repository.ComponentRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpdateComponentCommand implements Command<Component> {

    private final ComponentRepository repository;
    private final Long id;
    private final Component updatedData;


    @Override
    public CommandResult<Component> execute() {
        Component component = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Component not found"));

        component.setName(updatedData.getName());
        component.setType(updatedData.getType());

        repository.save(component);
        return null;
    }
}
