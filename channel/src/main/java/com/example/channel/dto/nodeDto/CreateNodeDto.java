package com.example.channel.dto.nodeDto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreateNodeDto {
    @JsonProperty("type")
    private String type;
    @JsonProperty("idNode")
    private String idNode;
//    @JsonProperty("isLeaf")
//    private Boolean isParent;
    @JsonProperty("parentKey")
    private String parentId;
}
