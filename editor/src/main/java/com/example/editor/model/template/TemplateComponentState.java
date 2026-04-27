package com.example.editor.model.template;

import com.example.editor.model.component.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

@Entity
@Data
@Table(name = "template_component_state", schema = "editor")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateComponentState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id", nullable = false)
    private TemplateComponent component;

    private String name;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode image;

    @Column(name = "is_default")
    private Boolean isDefault = false;
}
