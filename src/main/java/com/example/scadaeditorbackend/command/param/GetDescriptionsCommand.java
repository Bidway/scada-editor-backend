package com.example.scadaeditorbackend.command.param;

import com.example.scadaeditorbackend.command.config.Command;
import com.example.scadaeditorbackend.command.config.CommandResult;
import com.example.scadaeditorbackend.dto.paramDto.DescriptionRespose;
import com.example.scadaeditorbackend.repository.DescriptionRepository;

public class GetDescriptionsCommand implements Command<DescriptionRespose> {
    private final DescriptionRepository descriptionRepository;

    public GetDescriptionsCommand(DescriptionRepository descriptionRepository) {
        this.descriptionRepository = descriptionRepository;
    }

    @Override
    public CommandResult<DescriptionRespose> execute() {
        DescriptionRespose descriptionRespose = new DescriptionRespose();
        descriptionRespose.setDescriptions(descriptionRepository.findAll());
        return new CommandResult<DescriptionRespose>(null,null,null,null,null,null,descriptionRespose);
    }
}