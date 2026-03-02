package com.example.scadaeditorbackend.editor.service.Impl;

import com.example.scadaeditorbackend.config.command.CommandManager;
import com.example.scadaeditorbackend.editor.command.property.CreatePropertyCommand;
import com.example.scadaeditorbackend.editor.command.property.DeletePropertyCommand;
import com.example.scadaeditorbackend.editor.command.property.UpdatePropertyCommand;
import com.example.scadaeditorbackend.editor.model.ComponentProperty;
import com.example.scadaeditorbackend.editor.repository.ComponentPropertyRepository;
import com.example.scadaeditorbackend.editor.service.ComponentPropertyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ComponentPropertyServiceImpl
        implements ComponentPropertyService {

    private final ComponentPropertyRepository repository;
    private final CommandManager commandManager;

    @Override
    public ComponentProperty create(ComponentProperty property) {

        CreatePropertyCommand command =
                new CreatePropertyCommand(repository, property);
        return commandManager.execute(command);
    }

    @Override
    public ComponentProperty update(Long id, ComponentProperty property) {

        UpdatePropertyCommand command =
                new UpdatePropertyCommand(repository, id, property);

        return commandManager.execute(command);
    }

    @Override
    public void delete(Long id) {

        DeletePropertyCommand command =
                new DeletePropertyCommand(repository, id);

        command.execute();
    }

    @Override
    public ComponentProperty getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Property not found"));
    }

    @Override
    public List<ComponentProperty> getByComponentId(Long componentId) {
        return repository.findByComponentId(componentId);
    }
}
