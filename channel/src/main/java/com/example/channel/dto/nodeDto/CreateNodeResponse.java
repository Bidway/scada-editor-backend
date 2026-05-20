package com.example.channel.dto.nodeDto;

import com.example.channel.dto.paramDto.ParamDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class CreateNodeResponse {
    private NodeDto nodeDTO;
    private List<ParamDto> params = new ArrayList<>();
    private UUID batchId;
}
