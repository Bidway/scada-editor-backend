package com.example.scadaeditorbackend.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NodeResponse {
    private List<NodeDTO> nodes = new ArrayList<>();
    private List<ParamDTO> params = new ArrayList<>();
}

