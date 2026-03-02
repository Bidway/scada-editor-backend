package com.example.scadaeditorbackend.editor.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.Map;

import org.hibernate.annotations.Type;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;

@Entity
@Table(name = "binding")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Binding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private Component component;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_property_id", nullable = false)
    private ComponentProperty componentProperty;

    private String name;

    @Column(columnDefinition = "text")
    private String script;
}
