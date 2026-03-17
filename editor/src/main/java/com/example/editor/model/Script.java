package com.example.editor.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "scripts", schema = "editor")
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
