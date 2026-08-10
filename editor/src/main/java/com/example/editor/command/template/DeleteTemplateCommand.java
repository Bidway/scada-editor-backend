package com.example.editor.command.template;

import com.example.shared.command.Command;
import com.example.shared.command.CommandResult;
import com.example.editor.repository.template.TemplateFacePlateRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DeleteTemplateCommand implements Command<Void> {

    private final TemplateFacePlateRepository templateRepository;
    private final Long templateId;
    private final String userName;

    @Override
    public CommandResult<Void> execute() {
        templateRepository.deleteById(templateId);
        return new CommandResult<>(userName, "template", templateId, "DELETE_TEMPLATE", null, null, null);
    }
}