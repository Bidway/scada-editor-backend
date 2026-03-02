package com.example.scadaeditorbackend.editor.command.component;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.editor.repository.ComponentRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DeleteComponentCommand implements Command<Void> {

    private final ComponentRepository repository;
    private final Long id;


    @Override
    public CommandResult<Void> execute() {
        repository.deleteById(id);
        return null;
    }
}
