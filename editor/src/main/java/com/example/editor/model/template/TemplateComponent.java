package com.example.editor.model.template;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "template_component", schema = "editor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

//    @Type(JsonBinaryType.class)
//    @Column(columnDefinition = "jsonb")
//    private JsonNode image;
    @OneToMany(
            mappedBy = "component",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<TemplateComponentState> states = new ArrayList<>();

    @OneToMany(mappedBy = "component", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TemplateScript> scripts = new ArrayList<>();

    @OneToMany(mappedBy = "component", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TemplateComponentProperty> properties = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private TemplateFacePlate template;

    @Column(name = "template_id", insertable = false, updatable = false)
    private Long templateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private TemplateComponent parent;

    @Column(name = "parent_id", insertable = false, updatable = false)
    private Long parentId;


    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TemplateComponent> children = new ArrayList<>();
}
