package com.example.editor.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "component_property", schema = "editor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComponentProperty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private Component component;

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
