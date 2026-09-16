package com.example.runtime.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProcedureStateRepository extends JpaRepository<ProcedureStateEntity, Long> {

    Optional<ProcedureStateEntity> findByProjectIdAndRecipeId(Long projectId, String recipeId);

    List<ProcedureStateEntity> findByProjectIdIn(Collection<Long> projectIds);

    void deleteByProjectIdAndRecipeId(Long projectId, String recipeId);
}
