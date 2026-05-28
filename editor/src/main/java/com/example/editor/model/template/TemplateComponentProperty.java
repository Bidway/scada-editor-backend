package com.example.editor.model.template;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "template_component_property", schema = "editor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateComponentProperty {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private TemplateComponent component;

    private String tagId;

    @Column(nullable = false)
    private String propertyType;

    private String description;

    @Column(nullable = false)
    private String valueType;

    private String defaultValue;

    @Column(nullable = false)
    private Boolean logging = false;

    @Column(columnDefinition = "text")
    private String onChange;
}
