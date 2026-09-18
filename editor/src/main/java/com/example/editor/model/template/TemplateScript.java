package com.example.editor.model.template;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
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
    // Правило удаления из рабочей базы — в коде, чтобы чистая схема его повторяла (scada-854).
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TemplateComponent component;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String script;

    @Column(nullable = false)
    private Boolean displayed = false;
}
