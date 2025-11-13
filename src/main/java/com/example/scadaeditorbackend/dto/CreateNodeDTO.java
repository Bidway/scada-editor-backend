package com.example.scadaeditorbackend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreateNodeDTO {
    @JsonProperty("type")
    private String type;
    @JsonProperty("title")
    private String name;
    @JsonProperty("isLeaf")
    private Boolean isParent;
    @JsonProperty("parentKey")
    private String parentId;
}
