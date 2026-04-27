package com.example.editor.repository.template;

import com.example.editor.model.template.TemplateComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TemplateComponentRepository extends JpaRepository<TemplateComponent, Long> {
}
