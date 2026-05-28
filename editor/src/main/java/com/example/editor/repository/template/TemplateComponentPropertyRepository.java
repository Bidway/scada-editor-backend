package com.example.editor.repository.template;

import com.example.editor.model.template.TemplateComponent;
import com.example.editor.model.template.TemplateComponentProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TemplateComponentPropertyRepository extends JpaRepository<TemplateComponentProperty, Long> {
}
