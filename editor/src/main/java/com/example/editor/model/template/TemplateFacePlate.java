package com.example.editor.model.template;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "template_faceplate", schema = "editor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateFacePlate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "root_component_id")
    // Правило удаления из рабочей базы — в коде, чтобы чистая схема его повторяла (scada-854).
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private TemplateComponent rootComponent;
}
