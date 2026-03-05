package com.example.channel.dto.nodeDto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreateNodeDto {
    @JsonProperty("type")
    private String type;
    @JsonProperty("title")
    private String name;
    @JsonProperty("isLeaf")
    private Boolean isParent;
    @JsonProperty("parentKey")
    private String parentId;
}
