package com.example.channel.command.node;

import com.example.channel.command.param.DeleteNodeParamCommand;
import com.example.channel.config.command.Command;
import com.example.channel.config.command.CommandManager;
import com.example.channel.config.command.CommandResult;
import com.example.channel.model.Node;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.NodeRepository;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public class DeleteNodeCommand implements Command<Void> {

    private final Long userId;
    private final NodeRepository nodeRepository;
    private final ParamRepository paramRepository;
    private final CommandManager commandManager;
    private final ObjectMapper mapper;
    private final String idNode;

    public DeleteNodeCommand(
            Long userId,
            NodeRepository nodeRepository,
            ParamRepository paramRepository,
            CommandManager commandManager,
            ObjectMapper mapper,
            String idNode
    ) {
        this.userId = userId;
        this.nodeRepository = nodeRepository;
        this.paramRepository = paramRepository;
        this.commandManager = commandManager;
        this.mapper = mapper;
        this.idNode = idNode;
    }

    @Override
    public CommandResult<Void> execute() {

        Node node = nodeRepository.findByIdNode(idNode)
                .orElseThrow();

        List<NodeParam> params = paramRepository.findByNode(node)
                .orElseThrow();

        for (NodeParam param : params) {

            commandManager.execute(
                    new DeleteNodeParamCommand(
                            userId,
                            mapper,
                            paramRepository,
                            param.getId()
                    )
            );
        }

        nodeRepository.delete(node);

        return new CommandResult<>(
                userId,
                "NODE",
                node.getId(),
                "DELETE_NODE",
                mapper.valueToTree(node),
                mapper.valueToTree(node),
                null
        );
    }
}
