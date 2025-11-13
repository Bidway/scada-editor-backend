package com.example.scadaeditorbackend.dto;

import com.example.scadaeditorbackend.model.NodeParam;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CreateNodeResponse {
    private NodeDTO nodeDTO;
    private List<ParamDTO> params = new ArrayList<>();;
}
