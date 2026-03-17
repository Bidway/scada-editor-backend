package com.example.channel.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "node", schema = "channel")
@Getter
@Setter
public class Node {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_node", unique = true, nullable = false)
    private String idNode;

}
