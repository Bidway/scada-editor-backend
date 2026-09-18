package com.example.editor.model.template;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
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
    // Без каскада на чистой схеме удаление шаблона падает на FK (scada-854).
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TemplateComponent component;

    private String tagId;

    @Column(nullable = false)
    private String propertyType;

    private String description;

    @Column(nullable = false)
    private String valueType;

    private String defaultValue;

    /** Номер для представления — переносится в ComponentProperty при разворачивании шаблона. */
    private Integer position;

    @Column(nullable = false)
    private Boolean logging = false;

    @Column(columnDefinition = "text")
    private String onChange;
}
