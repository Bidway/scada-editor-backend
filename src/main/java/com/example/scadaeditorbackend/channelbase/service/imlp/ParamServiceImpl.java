package com.example.scadaeditorbackend.channelbase.service.imlp;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandLog;
import com.example.scadaeditorbackend.config.command.CommandManager;
import com.example.scadaeditorbackend.config.command.UndoHandler;
import com.example.scadaeditorbackend.channelbase.command.param.CreateNodeParamCommand;
import com.example.scadaeditorbackend.channelbase.command.param.GetDescriptionsCommand;
import com.example.scadaeditorbackend.channelbase.command.param.UpdateNodeParamCommand;
import com.example.scadaeditorbackend.channelbase.command.param.UpdateNodeParamUndoHandler;
import com.example.scadaeditorbackend.channelbase.dto.paramDto.CreateParamDto;
import com.example.scadaeditorbackend.channelbase.dto.KeyValue;
import com.example.scadaeditorbackend.channelbase.dto.paramDto.DescriptionRespose;
import com.example.scadaeditorbackend.channelbase.dto.paramDto.ParamDto;
import com.example.scadaeditorbackend.channelbase.mapper.NodeMapper;
import com.example.scadaeditorbackend.channelbase.repository.CommandLogRepository;
import com.example.scadaeditorbackend.channelbase.repository.DescriptionRepository;
import com.example.scadaeditorbackend.channelbase.repository.NodeRepository;
import com.example.scadaeditorbackend.channelbase.repository.ParamRepository;
import com.example.scadaeditorbackend.channelbase.service.ParamService;
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
    private final ObjectMapper objectMapper;
    private final CommandManager commandManager;
    private final CommandLogRepository commandLogRepository;

    @Override
    public void deleteParamById(Long id) {
        paramRepository.deleteById(id);
    }

    @Override
    public ParamDto createParam(CreateParamDto createParamDTO) {
        Command<ParamDto> cmd = new CreateNodeParamCommand(
                1,
                objectMapper,
                descriptionRepository,
                nodeRepository,
                paramRepository,
                createParamDTO,
                nodeMapper
        );
        ParamDto dto = commandManager.execute(cmd);
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

    @Override
    public DescriptionRespose getDescriptions() {
        Command<DescriptionRespose> cmd = new GetDescriptionsCommand(
                descriptionRepository
        );
        DescriptionRespose respose = commandManager.execute(cmd);
        return respose;
    }
}
