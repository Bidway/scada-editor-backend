package com.example.channel.service.imlp;

import com.example.channel.command.CrudCommand;
import com.example.channel.config.command.CommandManager;
import com.example.channel.dto.paramDto.CreateParamDto;
import com.example.channel.dto.KeyValue;
import com.example.channel.dto.paramDto.DescriptionResponse;
import com.example.channel.dto.paramDto.ParamDto;
import com.example.channel.mapper.NodeMapper;
import com.example.channel.model.Description;
import com.example.channel.model.Node;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.DescriptionRepository;
import com.example.channel.repository.NodeRepository;
import com.example.channel.repository.ParamRepository;
import com.example.channel.service.ParamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ParamServiceImpl implements ParamService {

    private final ParamRepository paramRepository;
    private final DescriptionRepository descriptionRepository;
    private final NodeRepository nodeRepository;
    private final NodeMapper nodeMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper mapper;
    private final CommandManager commandManager;

    @Override
    public void deleteParamById(Long id, String userName) {

        NodeParam param = paramRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Param not found: " + id));

        CrudCommand<NodeParam> cmd = new CrudCommand<>(
                userName,
                CrudCommand.Action.DELETE,
                paramRepository,
                mapper,
                param,
                null,
                NodeParam::getId
        );

        commandManager.execute(cmd);
    }

    @Override
    public ParamDto createParam(CreateParamDto dto,String userName) {

        Node node = nodeRepository.findByIdNode(dto.getIdNode())
                .orElseThrow(() -> new RuntimeException("Node not found"));

        NodeParam param = new NodeParam();
        param.setIdNode(node.getIdNode());
        param.setIdType(descriptionRepository.findById(dto.getId()).getId());
        param.setValue(dto.getValue());

        CrudCommand<NodeParam> cmd = new CrudCommand<>(
                userName,
                CrudCommand.Action.CREATE,
                paramRepository,
                mapper,
                param,
                null,
                NodeParam::getId
        );

        NodeParam saved = commandManager.execute(cmd).getResult();

        List<Description> descriptions = descriptionRepository.findAll();

        return nodeMapper.toDto(saved, descriptions);
    }

    @Override
    @Transactional
    public ResponseEntity<Void> updateParams(List<KeyValue> keyValues, String userName) {

        for (KeyValue kv : keyValues) {

            NodeParam param = paramRepository.findById(kv.getKey())
                    .orElseThrow(() -> new RuntimeException("Param not found"));

            // копия состояния для undo
            NodeParam beforeUpdate = new NodeParam();
            beforeUpdate.setId(param.getId());
            beforeUpdate.setIdNode(param.getIdNode());
            beforeUpdate.setIdType(param.getIdType());
            beforeUpdate.setValue(param.getValue());

            param.setValue(kv.getValue());

            CrudCommand<NodeParam> cmd = new CrudCommand<>(
                    userName,
                    CrudCommand.Action.UPDATE,
                    paramRepository,
                    mapper,
                    param,
                    beforeUpdate,
                    NodeParam::getId
            );

            NodeParam updated = commandManager.execute(cmd).getResult();

            messagingTemplate.convertAndSend(
                    "/topic/param/" + updated.getId(),
                    updated.getValue()
            );
        }

        return ResponseEntity.ok().build();
    }

    @Override
    public DescriptionResponse getDescriptions() {

        DescriptionResponse response = new DescriptionResponse();
        response.setDescriptions(descriptionRepository.findAll());
        return response;
    }
}
