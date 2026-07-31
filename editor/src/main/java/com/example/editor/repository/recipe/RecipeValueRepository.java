package com.example.editor.repository.recipe;

import com.example.editor.model.recipe.RecipeValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RecipeValueRepository extends JpaRepository<RecipeValue, Long> {

    /**
     * Переносит значения на новое имя строки во всех наборах таблицы. Вызывается при точечном
     * переименовании свойства — единственной операции, где переименование отличимо от «удалили
     * строку, добавили другую» (при wholesale-сохранении таблицы отличить нельзя).
     *
     * @return сколько значений перенесено — для лога
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecipeValue v set v.rowName = :newName "
            + "where v.rowName = :oldName and v.recipe.componentId = :componentId")
    int renameRow(@Param("componentId") Long componentId,
                  @Param("oldName") String oldName,
                  @Param("newName") String newName);
}
