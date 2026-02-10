package com.example.scadaeditorbackend.dto.paramDto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreateParamDto {

    @JsonProperty("parentKey")
    String idNode;
    String name;
    String value;
}
