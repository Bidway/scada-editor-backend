package com.example.channel.repository;

import com.example.channel.model.Node;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NodeRepository extends JpaRepository<Node, Long> {

    @Query(value = "SELECT n.* FROM node n " +
            "WHERE n.id_node LIKE 'dev%' " +
            "AND EXISTS (SELECT 1 FROM param p JOIN description d ON p.id_type = d.id " +
            "           WHERE p.id_node = n.id_node AND d.name = 'Площадка' AND p.value = :site) " +
            "AND EXISTS (SELECT 1 FROM param p JOIN description d ON p.id_type = d.id " +
            "           WHERE p.id_node = n.id_node AND d.name = 'Проект' AND p.value = :project)",
            nativeQuery = true)
    List<Node> findDevicesBySiteAndProject(@Param("site") String site, @Param("project") String project);

    @Query(value = "SELECT n.* FROM node n WHERE n.parent_id IN :parentIds", nativeQuery = true)
    List<Node> findByParentIds(@Param("parentIds") List<String> parentIds);

    @Transactional
    void deleteNodeByIdNode(String idNode);

    Node getNodeByIdNode(String idNode);

}

