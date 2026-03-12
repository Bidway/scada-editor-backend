package com.example.channel.repository;

import com.example.channel.model.Node;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NodeRepository extends JpaRepository<Node, Long> {



    @Transactional
    void deleteNodeByIdNode(String idNode);

    Node getNodeByIdNode(String idNode);

    Optional<Node> findByIdNode(String idNode);

    // --- Находим все узлы, которые начинаются с rootPath ---
    @Query("SELECT n FROM Node n WHERE n.idNode LIKE CONCAT(:rootPath, '.%') ORDER BY n.idNode")
    List<Node> findByIdNodeStartingWith(@Param("rootPath") String rootPath);

    @Query("""
    SELECT n FROM Node n
    WHERE n.idNode LIKE CONCAT(:rootPath, '.%')
    AND
    (
    LENGTH(n.idNode) - LENGTH(REPLACE(n.idNode,'.',''))
    ) =
    (
    LENGTH(:rootPath) - LENGTH(REPLACE(:rootPath,'.','')) + 1
    )
    ORDER BY n.idNode
    """)
    List<Node> findDirectChildren(@Param("rootPath") String rootPath);

    @Query("""
    SELECT n FROM Node n
    WHERE (LENGTH(n.idNode) - LENGTH(REPLACE(n.idNode,'.',''))) = 0
    ORDER BY n.idNode
    """)
    List<Node> findRootNodes();
}

