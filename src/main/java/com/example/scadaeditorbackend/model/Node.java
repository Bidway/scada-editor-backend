package com.example.scadaeditorbackend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.redis.core.RedisHash;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "node")
@Getter
@Setter
public class Node {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_node", unique = true, nullable = false)
    private String idNode;

    @Transient // Это поле не будет сохраняться в БД
    private String nodeType; // "dev", "sub" или "cha"

    @Column(name = "name")
    private String name;

    @Column(name = "parent_id")
    private String parentId;

//
//    @OneToMany(mappedBy = "node", cascade = CascadeType.ALL, orphanRemoval = true)
//    @JsonIgnore // Полностью исключаем из JSON
//    private List<NodeParam> nodeParams = new ArrayList<>();

    @PrePersist
    public void generateIdNode() {
        if (nodeType == null || (!nodeType.equals("dev") && !nodeType.equals("sub") && !nodeType.equals("cha"))) {
            throw new IllegalArgumentException("Node type must be 'dev', 'sub' or 'cha'");
        }
    }

    @PostPersist
    public void updateIdNode() {
        this.idNode = this.nodeType + this.id;
        // Если нужно сразу сохранить в БД, потребуется EntityManager
    }
}
