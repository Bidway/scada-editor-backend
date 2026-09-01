package com.example.editor.model.template;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Привязка свойства шаблона к скрипту отрисовки — то же самое, что {@code Binding} у реального
 * компонента, но на стороне шаблона: свойство здесь ссылается на {@link TemplateComponentProperty},
 * а не на боевое {@code ComponentProperty}. Разворачивается в обычный {@code Binding} вместе с
 * остальным деревом шаблона при размещении экземпляра на сцене.
 */
@Entity
@Table(name = "template_component_binding", schema = "editor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateComponentBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * {@code ON DELETE CASCADE} — иначе удаление шаблона падает {@code DataIntegrityViolationException}:
     * {@code TemplateFacePlate.rootComponent} объявлена без cascade, и удаление дерева компонентов
     * держится на каскаде внешнего ключа в БД (как у {@code template_component_state}/
     * {@code _property}), а не на JPA-каскаде.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TemplateComponent component;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_property_id", nullable = false)
    private TemplateComponentProperty componentProperty;

    private String name;

    @Column(columnDefinition = "text")
    private String script;
}
