package com.example.scadaeditorbackend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Getter;

@Data

public class ParamDTO {
    @JsonProperty("key")
    private Long id;
    @JsonProperty("parentKey")
    private String idNode;
    @JsonProperty("name")
    private String name;
    @JsonProperty("type")
    private String type;
    @JsonProperty("value")
    private String value;
}
