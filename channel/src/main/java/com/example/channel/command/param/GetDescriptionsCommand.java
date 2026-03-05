package com.example.channel.command.param;

import com.example.channel.config.command.Command;
import com.example.channel.config.command.CommandResult;
import com.example.channel.dto.paramDto.DescriptionRespose;
import com.example.channel.repository.DescriptionRepository;

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