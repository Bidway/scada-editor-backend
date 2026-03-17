package com.example.channel.repository;

import com.example.channel.model.Node;
import com.example.channel.model.NodeParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParamRepository extends JpaRepository<NodeParam, Long> {
    @Query(value = "SELECT p.* FROM channel.param p WHERE p.id_node IN :nodeIds", nativeQuery = true)
    List<NodeParam> findParamsByNodeIds(@Param("nodeIds") List<String> nodeIds);

    List<NodeParam> findAllByIdIn(List<Long> ids);
    Optional<NodeParam> findById(Long id);
    Optional<List<NodeParam>> findByIdNode(String idNode);

}
