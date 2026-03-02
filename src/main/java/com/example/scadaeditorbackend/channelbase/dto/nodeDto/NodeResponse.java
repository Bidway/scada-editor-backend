package com.example.scadaeditorbackend.channelbase.dto.nodeDto;

import com.example.scadaeditorbackend.channelbase.dto.paramDto.ParamDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NodeResponse {
    private List<NodeDto> nodes = new ArrayList<>();
    private List<ParamDto> params = new ArrayList<>();
}

