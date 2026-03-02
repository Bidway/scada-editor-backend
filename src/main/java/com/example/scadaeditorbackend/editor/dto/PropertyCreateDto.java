package com.example.scadaeditorbackend.editor.dto;

import com.fasterxml.jackson.databind.JsonNode;

public class PropertyCreateDto {
    private String component_id;
    private String property_type;
    private String tag_id;
    private String description;
    private String value_type;
    private String default_value;
    private boolean logging;
    private JsonNode onChange;
}
