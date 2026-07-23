package com.example.editor.model.component;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;

/**
 * Обработчик события компонента (клик оператора и т.п.). Принадлежит <b>компоненту</b>,
 * а не его состоянию: кнопка кликабельна независимо от того, какой картинкой она сейчас
 * нарисована. Раньше обработчики лежали в {@code component_state.image.events} — jsonb
 * графики, который фронт перезаписывает целиком при редактировании фигуры.
 * <p>
 * Исполняется на фронте; если нужно записать тег — обработчик зовёт {@code runScript(...)},
 * а тот уже уходит на бэк через ACTION.
 */
@Entity
@Table(
        name = "component_event",
        schema = "editor",
        uniqueConstraints = @UniqueConstraint(
                name = "component_event_uk",
                columnNames = {"component_id", "event_type"})
)
@Check(constraints = EventTypes.CHECK)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComponentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private Component component;

    /** Одно из {@link EventTypes}. */
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(columnDefinition = "text", nullable = false)
    private String script;
}
