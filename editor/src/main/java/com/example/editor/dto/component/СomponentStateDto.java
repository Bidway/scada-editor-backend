package com.example.editor.dto.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import lombok.Data;
import org.hibernate.annotations.Type;

@Data
public class СomponentStateDto {
    private String name;
    private JsonNode image;
    private Boolean isDefault;
}
