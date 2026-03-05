package com.example.channel.service.imlp;

import com.example.channel.config.command.Command;
import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandManager;
import com.example.channel.config.command.UndoHandler;
import com.example.channel.command.param.CreateNodeParamCommand;
import com.example.channel.command.param.GetDescriptionsCommand;
import com.example.channel.command.param.UpdateNodeParamCommand;
import com.example.channel.command.param.UpdateNodeParamUndoHandler;
import com.example.channel.dto.paramDto.CreateParamDto;
import com.example.channel.dto.KeyValue;
import com.example.channel.dto.paramDto.DescriptionRespose;
import com.example.channel.dto.paramDto.ParamDto;
import com.example.channel.mapper.NodeMapper;
import com.example.channel.config.command.CommandLogRepository;
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
