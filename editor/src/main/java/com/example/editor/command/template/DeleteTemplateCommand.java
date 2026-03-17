package com.example.editor.command.template;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.repository.TemplateFacePlateRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DeleteTemplateCommand implements Command<Void> {

    private final TemplateFacePlateRepository templateRepository;
    private final Long templateId;

    @Override
    public CommandResult<Void> execute() {
        templateRepository.deleteById(templateId);
        return new CommandResult<>("david", "template", templateId, "DELETE_TEMPLATE", null, null, null);
    }
}