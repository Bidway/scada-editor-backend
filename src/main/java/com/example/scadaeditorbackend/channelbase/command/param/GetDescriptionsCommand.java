package com.example.scadaeditorbackend.channelbase.command.param;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.channelbase.dto.paramDto.DescriptionRespose;
import com.example.scadaeditorbackend.channelbase.repository.DescriptionRepository;

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