package com.example.scadaeditorbackend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class NodeDTO {
    @JsonProperty("key")
    private String idNode;
    @JsonProperty("title")
    private String name;
    @JsonProperty("isLeaf")
    private Boolean isParent;
    @JsonProperty("parentKey")
    private String parentId;
}
