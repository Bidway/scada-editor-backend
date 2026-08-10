package com.example.channel.service.imlp;

import com.example.channel.command.CreateParamCommand;
import com.example.channel.command.DeleteParamCommand;
import com.example.channel.command.UpdateParamCommand;
import com.example.channel.config.command.CommandBatch;
import com.example.channel.config.command.CommandManager;
import com.example.channel.dto.paramDto.CreateParamDto;
import com.example.channel.dto.KeyValue;
import com.example.channel.dto.paramDto.DescriptionResponse;
import com.example.channel.dto.paramDto.KafkaBindingDto;
import com.example.channel.dto.paramDto.ParamDto;
import com.example.channel.exception.NotFoundException;
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
                .orElseThrow(() -> new NotFoundException("Param not found: " + id));

        commandManager.execute(new DeleteParamCommand(paramRepository, param, mapper, userName, null));
    }

    @Override
    public ParamDto createParam(CreateParamDto dto, String userName) {
        Node node = nodeRepository.findByIdNode(dto.getIdNode())
                .orElseThrow(() -> new NotFoundException("Node not found: " + dto.getIdNode()));

        Description description = descriptionRepository.findById(dto.getId())
                .orElseThrow(() -> new NotFoundException("Description not found: " + dto.getId()));
        NodeParam param = new NodeParam();
        param.setIdNode(node.getIdNode());
        param.setIdType(description.getId());
        param.setValue(dto.getValue());

        NodeParam saved = commandManager.execute(
                new CreateParamCommand(paramRepository, param, mapper, userName, null)
        );

        List<Description> descriptions = descriptionRepository.findAll();
        return nodeMapper.toDto(saved, descriptions);
    }

    @Override
    @Transactional
    public ResponseEntity<Void> updateParams(List<KeyValue> keyValues, String userName) {
        CommandBatch batch = keyValues.size() > 1 ? CommandBatch.start() : null;

        for (KeyValue kv : keyValues) {
            NodeParam param = paramRepository.findById(kv.getKey())
                    .orElseThrow(() -> new NotFoundException("Param not found: " + kv.getKey()));

            NodeParam before = new NodeParam();
            before.setId(param.getId());
            before.setIdNode(param.getIdNode());
            before.setIdType(param.getIdType());
            before.setValue(param.getValue());

            param.setValue(kv.getValue());

            NodeParam updated = commandManager.execute(
                    new UpdateParamCommand(paramRepository, before, param, mapper, userName, batch)
            );

            messagingTemplate.convertAndSend("/topic/param/" + updated.getId(), updated.getValue());
        }

        return ResponseEntity.ok().build();
    }

    @Override
    public DescriptionResponse getDescriptions() {
        DescriptionResponse response = new DescriptionResponse();
        response.setDescriptions(descriptionRepository.findAll());
        return response;
    }

    @Override
    public List<KafkaBindingDto> resolveKafkaBindings(List<Long> paramIds) {
        if (paramIds == null || paramIds.isEmpty()) {
            return List.of();
        }
        return paramRepository.findAllByIdIn(paramIds).stream()
                .map(p -> new KafkaBindingDto(p.getId(), p.getIdNode()))
                .toList();
    }
}
