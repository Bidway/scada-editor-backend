package com.example.editor.command.component;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.repository.ComponentRepository;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class DeleteComponentCommand implements Command<Void> {

    private final ComponentRepository repository;
    private final List<Long> ids;


    @Override
    public CommandResult<Void> execute() {
        ids.stream().forEach(id -> repository.deleteById(id));
        return null;
    }
}
