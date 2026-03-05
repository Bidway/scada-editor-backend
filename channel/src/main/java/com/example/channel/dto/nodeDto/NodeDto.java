package com.example.channel.dto.nodeDto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class NodeDto {
    @JsonProperty("key")
    private String idNode;
    @JsonProperty("title")
    private String name;
    @JsonProperty("isLeaf")
    private Boolean isParent;
    @JsonProperty("parentKey")
    private String parentId;
}
