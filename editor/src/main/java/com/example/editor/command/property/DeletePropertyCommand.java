package com.example.editor.command.property;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.repository.ComponentPropertyRepository;
import lombok.RequiredArgsConstructor;

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
