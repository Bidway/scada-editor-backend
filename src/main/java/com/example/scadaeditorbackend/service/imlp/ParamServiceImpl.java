package com.example.scadaeditorbackend.service.imlp;

import com.example.scadaeditorbackend.command.*;
import com.example.scadaeditorbackend.dto.WsEvent;
import com.example.scadaeditorbackend.dto.paramDto.CreateParamDto;
import com.example.scadaeditorbackend.dto.KeyValue;
import com.example.scadaeditorbackend.dto.paramDto.ParamDto;
import com.example.scadaeditorbackend.mapper.NodeMapper;
import com.example.scadaeditorbackend.model.Description;
import com.example.scadaeditorbackend.model.Node;
import com.example.scadaeditorbackend.model.NodeParam;
import com.example.scadaeditorbackend.repository.CommandLogRepository;
import com.example.scadaeditorbackend.repository.DescriptionRepository;
import com.example.scadaeditorbackend.repository.NodeRepository;
import com.example.scadaeditorbackend.repository.ParamRepository;
import com.example.scadaeditorbackend.service.ParamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ParamServiceImpl implements ParamService {
    private final ParamRepository paramRepository;
    private final DescriptionRepository descriptionRepository;
    private final NodeRepository nodeRepository;
    private final NodeMapper nodeMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final CommandManager commandManager;
    private final CommandLogRepository commandLogRepository;

    @Override
    public void deleteParamById(Long id) {
        paramRepository.deleteById(id);
    }

    @Override
    public ParamDto createParam(CreateParamDto createParamDTO) {
        Description description = descriptionRepository.findByName(createParamDTO.getName());
        Node node = nodeRepository.getNodeByIdNode(createParamDTO.getIdNode());
        NodeParam nodeParam = new NodeParam();
        nodeParam.setIdType(description.getId());
        nodeParam.setNode(node);
        nodeParam.setValue(createParamDTO.getValue());
        NodeParam savedParam = paramRepository.save(nodeParam);
        ParamDto dto = nodeMapper.toDto(savedParam, description);
        return dto;
    }

    @Override
    @Transactional
    public ResponseEntity<Void> updateNodeParams(List<KeyValue> keyValues) {

        for(KeyValue kv : keyValues){
            Command cmd = new UpdateNodeParamCommand(
                    paramRepository,
                    messagingTemplate,
                    objectMapper,
                    1L,
                    kv.getKey(),
                    kv.getValue()
            );
            commandManager.execute(cmd);
        }
        return ResponseEntity.ok().build();
    }

    @Override
    @Transactional
    public ResponseEntity<Void> undoUpdateNodeParam(Long idCommandLog) {

            UndoHandler undo = new UpdateNodeParamUndoHandler(
                    paramRepository,
                    objectMapper,
                    1L
            );
            CommandLog commandLog = commandLogRepository.findById(idCommandLog)
                    .orElseThrow(() -> new IllegalArgumentException("CommandLog not found: " + idCommandLog));;
            commandManager.executeUndo(undo,commandLog);
        return ResponseEntity.ok().build();
    }
}
