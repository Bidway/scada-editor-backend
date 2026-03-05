package com.example.channel.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.*;

@Entity
@Table(name = "param")
@Getter
@Setter
public class NodeParam {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_node", referencedColumnName = "id_node")
    private Node node;

//    @JsonIgnore // Исключаем description из ответа
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "id_type")
//    private Description description;

    @Column(name = "id_type")
    private Long idType;

    @Column(name = "value")
    private String value;
}