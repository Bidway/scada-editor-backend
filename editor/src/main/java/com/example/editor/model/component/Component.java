package com.example.editor.model.component;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "component", schema = "editor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Component {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

//    @Type(JsonBinaryType.class)
//    @Column(columnDefinition = "jsonb")
//    private JsonNode image;
    @OneToMany(
        mappedBy = "component",
        cascade = CascadeType.ALL,
        orphanRemoval = true
    )
    private List<ComponentState> states = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Component parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Component> children = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "component", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Script> scripts = new ArrayList<>();

    // Порядок строк таблицы (и полей в редакторе) — по position; свойства без номера уходят
    // в конец, между собой упорядочены по id, то есть по порядку создания.
    @OneToMany(mappedBy = "component", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<ComponentProperty> properties = new ArrayList<>();

    @OneToMany(mappedBy = "component", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Binding> bindings = new ArrayList<>();

    @OneToMany(mappedBy = "component", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ComponentEvent> events = new ArrayList<>();
}
