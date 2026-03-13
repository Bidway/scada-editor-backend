package com.example.channel.service;

import com.example.channel.dto.paramDto.CreateParamDto;
import com.example.channel.dto.KeyValue;
import com.example.channel.dto.paramDto.DescriptionResponse;
import com.example.channel.dto.paramDto.ParamDto;
import org.springframework.http.ResponseEntity;

import java.util.List;

public interface ParamService {
    void deleteParamById(Long id, String userName);

    ParamDto createParam(CreateParamDto createParamDTO, String userName);

    ResponseEntity<Void> updateParams(List<KeyValue> keyValues, String userName);

    DescriptionResponse getDescriptions();

}
