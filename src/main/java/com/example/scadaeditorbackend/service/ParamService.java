package com.example.scadaeditorbackend.service;

import com.example.scadaeditorbackend.dto.CreateParamDTO;
import com.example.scadaeditorbackend.dto.KeyValue;
import com.example.scadaeditorbackend.dto.ParamDTO;
import org.springframework.http.ResponseEntity;

import java.util.List;

public interface ParamService {
    void deleteParamById(Long id);
    ParamDTO createParam(CreateParamDTO createParamDTO);
    ResponseEntity<Void> updateNodeParams(List<KeyValue> keyValues);
}
