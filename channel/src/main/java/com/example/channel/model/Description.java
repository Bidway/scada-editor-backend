package com.example.channel.model;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.*;

@Entity
@Table(name = "description")
@Getter
@Setter
public class Description {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "name", nullable = false)
    private String name;
//
//    @OneToMany(mappedBy = "description", cascade = CascadeType.ALL, orphanRemoval = true)
//    private List<NodeParam> nodeParams = new ArrayList<>();
}