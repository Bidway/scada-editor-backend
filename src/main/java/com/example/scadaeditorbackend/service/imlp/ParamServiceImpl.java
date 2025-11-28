package com.example.scadaeditorbackend.service.imlp;

import com.example.scadaeditorbackend.dto.paramDto.CreateParamDto;
import com.example.scadaeditorbackend.dto.KeyValue;
import com.example.scadaeditorbackend.dto.paramDto.ParamDto;
import com.example.scadaeditorbackend.mapper.NodeMapper;
import com.example.scadaeditorbackend.model.Description;
import com.example.scadaeditorbackend.model.Node;
import com.example.scadaeditorbackend.model.NodeParam;
import com.example.scadaeditorbackend.repository.DescriptionRepository;
import com.example.scadaeditorbackend.repository.NodeRepository;
import com.example.scadaeditorbackend.repository.ParamRepository;
import com.example.scadaeditorbackend.service.ParamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ParamServiceImpl implements ParamService {
    private final ParamRepository paramRepository;
    private final DescriptionRepository descriptionRepository;
    private final NodeRepository nodeRepository;
    private final NodeMapper nodeMapper;

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
    public ResponseEntity<Void> updateNodeParams(List<KeyValue> keyValues) {
        List<Long> ids = keyValues.stream().map(KeyValue::getKey).collect(Collectors.toList());
        List<NodeParam> nodeParams = paramRepository.findAllByIdIn(ids);

        Set<Long> missingIds = new HashSet<>(ids);
        nodeParams.forEach(param -> missingIds.remove(param.getId()));

        if (!missingIds.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        nodeParams.forEach(param -> {
            keyValues.stream()
                    .filter(kv -> kv.getKey().equals(param.getId()))
                    .findFirst()
                    .ifPresent(kv -> param.setValue(kv.getValue()));
        });

        paramRepository.saveAll(nodeParams);

        return ResponseEntity.ok().build();
    }
}
