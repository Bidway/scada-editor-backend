package com.example.editor.repository.template;

import com.example.editor.model.template.TemplateComponentBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TemplateComponentBindingRepository extends JpaRepository<TemplateComponentBinding, Long> {
}
