package com.example.scadaeditorbackend.dto.paramDto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data

public class ParamDto {
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
