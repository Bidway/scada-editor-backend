package com.example.editor.repository;

import com.example.editor.model.TemplateComponent;
import com.example.editor.model.TemplateFacePlate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TemplateFacePlateRepository extends JpaRepository<TemplateFacePlate, Long> {
}
