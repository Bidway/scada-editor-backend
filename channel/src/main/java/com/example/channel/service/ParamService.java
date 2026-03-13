package com.example.channel.service;

import com.example.channel.dto.paramDto.CreateParamDto;
import com.example.channel.dto.KeyValue;
import com.example.channel.dto.paramDto.DescriptionResponse;
import com.example.channel.dto.paramDto.ParamDto;
import org.springframework.http.ResponseEntity;

import java.util.List;

public interface ParamService {
    void deleteParamById(Long id);

    ParamDto createParam(CreateParamDto createParamDTO);

    ResponseEntity<Void> updateParams(List<KeyValue> keyValues);

    DescriptionResponse getDescriptions();

}
