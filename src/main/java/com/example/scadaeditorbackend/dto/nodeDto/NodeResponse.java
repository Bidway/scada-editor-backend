package com.example.scadaeditorbackend.dto.nodeDto;

import com.example.scadaeditorbackend.dto.paramDto.ParamDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NodeResponse {
    private List<NodeDto> nodes = new ArrayList<>();
    private List<ParamDto> params = new ArrayList<>();
}

