package com.example.editor.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ComponentCreateDto {
    private String key;
    private Long id;
    private String name;
    private List<ComponentCreateDto> children = new ArrayList<>();
    private Long version;
    private String type;
    private String parent_key;
    private Long parent_id;
    private JsonNode image;
}
