package com.example.runtime.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProcedureStateRepository extends JpaRepository<ProcedureStateEntity, Long> {

    Optional<ProcedureStateEntity> findByProjectIdAndRecipeId(Long projectId, String recipeId);

    List<ProcedureStateEntity> findByProjectIdIn(Collection<Long> projectIds);

    /**
     * Производный delete Spring Data сначала читает строки, потом удаляет их по одной, и вне
     * транзакции отказывается это делать. Сервис процедур транзакций не открывает, поэтому
     * транзакция объявлена здесь. Без неё abort отвечал 500, а строка оставалась, и прерванная
     * мойка оживала при следующем перезапуске runtime.
     */
    @Transactional
    void deleteByProjectIdAndRecipeId(Long projectId, String recipeId);
}
