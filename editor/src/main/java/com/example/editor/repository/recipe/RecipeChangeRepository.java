package com.example.editor.repository.recipe;

import com.example.editor.model.recipe.RecipeChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecipeChangeRepository extends JpaRepository<RecipeChange, Long> {

    List<RecipeChange> findByRecipeIdOrderByIdAsc(Long recipeId);
}
