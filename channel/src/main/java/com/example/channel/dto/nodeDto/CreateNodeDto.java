package com.example.channel.dto.nodeDto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateNodeDto {
    @NotNull
    @JsonProperty("type")
    private Long type;
    @NotBlank
    @JsonProperty("idNode")
    private String idNode;
//    @JsonProperty("isLeaf")
//    private Boolean isParent;
    @JsonProperty("parentKey")
    private String parentId;
}
