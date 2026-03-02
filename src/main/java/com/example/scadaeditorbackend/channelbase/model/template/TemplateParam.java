package com.example.scadaeditorbackend.channelbase.model.template;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name="template_param")
@Data
public class TemplateParam {
    @EmbeddedId
    private TemplateParamId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("templateId")
    private Template template;

    @Transient
    public Long getDescriptionId() {
        return id.getDescriptionId();
    }

    private int position;
}
