package com.example.editor.model.template;

import com.example.editor.model.component.EventTypes;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Обработчик события компонента шаблона — то же самое, что {@code ComponentEvent} у реального
 * компонента, но на стороне шаблона. {@code event_type} — то же {@link EventTypes}, что и у
 * боевого события: набор допустимых типов один на всю систему.
 */
@Entity
@Table(
        name = "template_component_event",
        schema = "editor",
        uniqueConstraints = @UniqueConstraint(
                name = "template_component_event_uk",
                columnNames = {"component_id", "event_type"})
)
@Check(constraints = EventTypes.CHECK)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateComponentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * {@code ON DELETE CASCADE} — как у {@code TemplateComponentBinding.component}: удаление
     * шаблона идёт через {@code deleteById} без загрузки дерева, и держится на каскаде внешнего
     * ключа в БД, а не на JPA-каскаде.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TemplateComponent component;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(columnDefinition = "text", nullable = false)
    private String script;
}
