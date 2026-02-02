package com.example.scadaeditorbackend.model.template;

import jakarta.persistence.Embeddable;
import lombok.Data;

import java.io.Serializable;

@Embeddable
@Data
public class TemplateParamId implements Serializable {
    private Long templateId;
    private Long descriptionId;
}
