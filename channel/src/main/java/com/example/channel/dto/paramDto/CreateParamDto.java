package com.example.channel.dto.paramDto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreateParamDto {

    @JsonProperty("parentKey")
    String idNode;
    long id;
    String value;
}
