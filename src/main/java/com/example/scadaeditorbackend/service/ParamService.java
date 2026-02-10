package com.example.scadaeditorbackend.service;

import com.example.scadaeditorbackend.dto.paramDto.CreateParamDto;
import com.example.scadaeditorbackend.dto.KeyValue;
import com.example.scadaeditorbackend.dto.paramDto.ParamDto;
import org.springframework.http.ResponseEntity;

import java.util.List;

public interface ParamService {
    void deleteParamById(Long id);
    ParamDto createParam(CreateParamDto createParamDTO);
    ResponseEntity<Void> updateNodeParams(List<KeyValue> keyValues);
    ResponseEntity<Void> undoUpdateNodeParam(Long idCommandLog);
}
