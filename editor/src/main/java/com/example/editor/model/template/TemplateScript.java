package com.example.editor.model.template;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "template_scripts", schema = "editor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateScript {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private TemplateComponent component;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String script;
}
