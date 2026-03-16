package com.example.channel.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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

//    @JsonIgnore
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "id_node", referencedColumnName = "id_node")
//    private Node node;
    @Column(name = "id_node")
    private String idNode;

    @Column(name = "id_type")
    private Long idType;

    @Column(name = "value")
    private String value;
}