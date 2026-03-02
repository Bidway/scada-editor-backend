package com.example.scadaeditorbackend.editor.command.property;

import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.editor.repository.ComponentPropertyRepository;
import lombok.RequiredArgsConstructor;
import com.example.scadaeditorbackend.config.command.Command;

@RequiredArgsConstructor
public class DeletePropertyCommand implements Command<Void> {

    private final ComponentPropertyRepository repository;
    private final Long id;


    @Override
    public CommandResult<Void> execute() {
        repository.deleteById(id);
        return null;
    }
}
