package com.example.editor.model.component;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "scripts",
        schema = "editor",
        // runScript('Открыть клапан') ищет скрипт по имени — одноимённые сделали бы вызов
        // неоднозначным (scada-95o).
        uniqueConstraints = @UniqueConstraint(
                name = "scripts_uk",
                columnNames = {"component_id", "name"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Script {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private Component component;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String script;
}
